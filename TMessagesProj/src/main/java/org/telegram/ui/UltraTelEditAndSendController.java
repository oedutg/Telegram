package org.telegram.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UltraTelBulkSendGuard;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UltraTelMediaDraft;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ShareAlert;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates a NEW outgoing media message from a locally available source.
 * Source messages are never edited, forwarded, or modified.
 *
 * DialogsActivity owns the existing Telegram recipient picker, caption input
 * and PhotoViewer editor; the ordinary media send pipeline publishes the result.
 */
public final class UltraTelEditAndSendController {
    private UltraTelEditAndSendController() {
    }

    public static boolean canOffer(MessageObject source, MessageObject.GroupedMessages group) {
        if (!BuildVars.isBetaApp() || source == null || source.messageOwner == null
                || source.messageOwner.action != null || source.messageOwner.noforwards
                || !source.canForwardMessage()) {
            return false;
        }
        if (group != null && group.messages != null && group.messages.size() > 1) {
            long groupId = source.getGroupId();
            if (groupId == 0) return false;
            for (MessageObject item : group.messages) {
                if (!canOfferSingleMedia(item) || item.getGroupId() != groupId) return false;
            }
            return true;
        }
        return canOfferSingleMedia(source);
    }

    private static boolean canOfferSingleMedia(MessageObject source) {
        return source != null && source.messageOwner != null
                && source.messageOwner.action == null
                && !source.messageOwner.noforwards && source.canForwardMessage()
                && (source.isPhoto() || source.isVideo() || source.isGif() || source.isSticker());
    }

    public static void open(ChatActivity host, ArrayList<MessageObject> sources) {
        if (!BuildVars.isBetaApp() || host == null || host.getParentActivity() == null
                || sources == null || sources.isEmpty()) {
            return;
        }

        UltraTelMediaDraft draft = UltraTelMediaDraft.fromMessages(sources, host.getCurrentAccount());
        if (draft == null) {
            Toast.makeText(host.getParentActivity(),
                    "Baixe todas as mídias antes de editar e enviar. Mensagens protegidas não são permitidas.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        ArrayList<MediaController.PhotoEntry> entries = new ArrayList<>();
        CharSequence initialCaption = "";
        for (UltraTelMediaDraft.Item item : draft.getItems()) {
            if (item.file == null || !item.file.isFile()) {
                Toast.makeText(host.getParentActivity(), "Arquivo de mídia indisponível.", Toast.LENGTH_SHORT).show();
                return;
            }
            // The native editor handles photos and videos. MP4 animations are
            // edited as videos; static WEBP stickers are edited as images.
            // Vector TGS stickers cannot be rasterized by the photo editor.
            String name = item.file.getName().toLowerCase(java.util.Locale.ROOT);
            if (item.sticker && name.endsWith(".tgs")) {
                Toast.makeText(host.getParentActivity(),
                        "Figurinha animada TGS ainda não pode ser editada como imagem.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            boolean isVideo = item.video || (item.gif && (name.endsWith(".mp4") || name.endsWith(".webm")))
                    || (item.sticker && name.endsWith(".webm"));
            MediaController.PhotoEntry entry = new MediaController.PhotoEntry(
                    0, 0, 0, item.file.getAbsolutePath(), 0, isVideo, 0, 0, 0);
            entry.caption = item.caption;
            if (isVideo) {
                entry.duration = (int) item.source.getDuration();
            }
            entries.add(entry);
            if (TextUtils.isEmpty(initialCaption) && !TextUtils.isEmpty(item.caption)) {
                initialCaption = item.caption;
            }
        }
        if (entries.isEmpty()) return;
        openMediaEditor(host, entries, initialCaption);
    }

    /**
     * Edit first, then choose a recipient. The ordinary Telegram forwarding
     * dialog must not be mistaken for an editor. Cancelling PhotoViewer never
     * opens the recipient picker or sends anything.
     */
    private static void openMediaEditor(ChatActivity host,
            ArrayList<MediaController.PhotoEntry> entries, CharSequence initialCaption) {
        final CharSequence[] editedCaption = {initialCaption};
        PhotoViewer viewer = PhotoViewer.getInstance();
        if (viewer.isVisible()) {
            viewer.closePhoto(false, false);
        }
        viewer.setParentActivity(host, host.getResourceProvider());
        viewer.hasCaptionForAllMedia = true;
        viewer.captionForAllMedia = initialCaption;
        boolean opened = viewer.openPhotoForSelect(new ArrayList<Object>(entries), 0, 0, false,
                new PhotoViewer.EmptyPhotoViewerProvider() {
                    @Override
                    public int getSelectedCount() {
                        return entries.size();
                    }

                    @Override
                    public boolean isPhotoChecked(int index) {
                        return index >= 0 && index < entries.size();
                    }

                    @Override
                    public int setPhotoChecked(int index, VideoEditedInfo videoEditedInfo) {
                        if (index >= 0 && index < entries.size()) {
                            entries.get(index).editedInfo = videoEditedInfo;
                        }
                        return index;
                    }

                    @Override
                    public boolean canScrollAway() {
                        return false;
                    }

                    @Override
                    public void onApplyCaption(CharSequence caption) {
                        editedCaption[0] = caption;
                    }

                    @Override
                    public void sendButtonPressed(int index, VideoEditedInfo videoEditedInfo,
                            boolean notify, int scheduleDate, int scheduleRepeatPeriod,
                            boolean forceDocument) {
                        if (index >= 0 && index < entries.size() && videoEditedInfo != null) {
                            entries.get(index).editedInfo = videoEditedInfo;
                        }
                        CharSequence currentCaption = PhotoViewer.getInstance().getCurrentCaptionText();
                        final CharSequence caption = currentCaption != null
                                ? currentCaption : editedCaption[0];
                        // PhotoViewer updates each PhotoEntry when a caption is
                        // applied. Do not overwrite the captions of every album
                        // item with the currently visible image's caption.
                        if (!entries.isEmpty() && caption != null) {
                            entries.get(0).caption = caption;
                        }
                        PhotoViewer.getInstance().closePhoto(true, false);
                        // Open the native recipient selector only after the
                        // native media editor has finished and closed.
                        AndroidUtilities.runOnUIThread(() -> {
                            if (host.getParentActivity() != null) {
                                openRecipientPicker(host, entries, caption, forceDocument);
                            }
                        }, 250);
                    }
                }, null);
        if (!opened) {
            Toast.makeText(host.getParentActivity(),
                    "Não foi possível abrir o editor nativo de mídia.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        viewer.setSelectionDisabled(true);
        viewer.setTitle("Editar e Enviar");
    }

    private static void openRecipientPicker(ChatActivity host,
            ArrayList<MediaController.PhotoEntry> entries, CharSequence initialCaption,
            boolean sendAsDocumentFromEditor) {
        if (host.getParentActivity() == null) return;

        // Reuse the exact grid, touch scrolling, 20-item pagination, search,
        // individual selection and select-all UI from Envio múltiplo.
        // Unlike the bulk sender, this path sends locally edited copies.
        ShareAlert picker = new ShareAlert(host.getParentActivity(),
                (ArrayList<MessageObject>) null, "", false, null, false);
        picker.setUltraTelEditedMediaRecipientCallback((dialogIds, withSound) ->
                dispatchEditedMedia(host, entries, initialCaption,
                        sendAsDocumentFromEditor, dialogIds, withSound));
        host.showDialog(picker);
    }

    private static boolean dispatchEditedMedia(ChatActivity host,
            ArrayList<MediaController.PhotoEntry> entries, CharSequence caption,
            boolean asDocument, ArrayList<Long> dialogIds, boolean withSound) {
        if (host.getParentActivity() == null || dialogIds == null
                || !UltraTelBulkSendGuard.isWithinRecipientLimit(dialogIds.size())) {
            return false;
        }

        if (copyEditedMedia(entries, caption).isEmpty()) {
            Toast.makeText(host.getParentActivity(),
                    "A edição não gerou arquivos válidos.", Toast.LENGTH_SHORT).show();
            return false;
        }

        final ArrayList<Long> paidDialogs = new ArrayList<>();
        int paidMessages = 0;
        for (Long did : dialogIds) {
            if (did == null || !UltraTelBulkSendGuard.isEligibleRecipient(did)) {
                Toast.makeText(host.getParentActivity(),
                        "Selecione apenas conversas privadas.", Toast.LENGTH_SHORT).show();
                return false;
            }
            MessagesController controller = host.getMessagesController();
            long price = controller.getSendPaidMessagesStars(did);
            if (price <= 0) {
                price = DialogObject.getMessagesStarsPrice(controller.isUserContactBlocked(did));
            }
            if (price > 0) {
                paidDialogs.add(did);
                paidMessages += entries.size();
            }
        }

        // Preserve paid-message confirmation from the native Telegram pipeline.
        AlertsCreator.ensurePaidMessagesMultiConfirmation(
                host.getCurrentAccount(), paidDialogs, paidMessages, prices -> {
                    for (Long did : dialogIds) {
                        ArrayList<SendMessagesHelper.SendingMediaInfo> media =
                                copyEditedMedia(entries, caption);
                        if (media.isEmpty()) continue;
                        Long price = prices == null ? null : prices.get(did);
                        SendMessagesHelper.prepareSendingMedia(host.getAccountInstance(), media, did,
                                null, null, null, null, asDocument, media.size() > 1, null,
                                withSound, 0, 0, 0, media.get(0).updateStickersOrder,
                                null, null, 0, false, price == null ? 0L : price, 0, null);
                    }
                });
        return true;
    }

    /**
     * Do not reuse mutable SendingMediaInfo instances across multiple chats.
     * An edited image uses its rendered path, never the source message object.
     */
    private static ArrayList<SendMessagesHelper.SendingMediaInfo> copyEditedMedia(
            List<MediaController.PhotoEntry> entries, CharSequence caption) {
        ArrayList<SendMessagesHelper.SendingMediaInfo> result = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            MediaController.PhotoEntry entry = entries.get(index);
            String path = !entry.isVideo && !TextUtils.isEmpty(entry.imagePath)
                    ? entry.imagePath : entry.path;
            if (TextUtils.isEmpty(path) || !new File(path).isFile()) {
                return new ArrayList<>();
            }
            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.path = path;
            info.thumbPath = entry.thumbPath;
            info.coverPath = entry.coverPath;
            info.isVideo = entry.isVideo;
            CharSequence itemCaption = index == 0 && caption != null ? caption : entry.caption;
            info.caption = TextUtils.isEmpty(itemCaption) ? null : itemCaption.toString();
            info.entities = entry.entities;
            info.masks = entry.stickers;
            info.videoEditedInfo = entry.editedInfo;
            info.ttl = entry.ttl;
            info.canDeleteAfter = false;
            result.add(info);
        }
        return result;
    }
}
