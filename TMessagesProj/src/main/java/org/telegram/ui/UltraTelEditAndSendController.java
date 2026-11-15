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
import org.telegram.messenger.MediaDataController;
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
                && (source.isPhoto() || source.isVideo() || source.isGif()
                    || source.isSticker() || source.isAnimatedSticker() || source.isVideoSticker());
    }

    private static boolean isAnimatedStickerMedia(MessageObject source) {
        return source != null && source.getDocument() instanceof TLRPC.TL_document
                && (source.isAnimatedSticker() || source.isVideoSticker()
                    || MessageObject.isAnimatedStickerDocument(source.getDocument(), true));
    }

    public static void open(ChatActivity host, ArrayList<MessageObject> sources) {
        if (!BuildVars.isBetaApp() || host == null || host.getParentActivity() == null
                || sources == null || sources.isEmpty()) {
            return;
        }

        // Animated TGS and WebM stickers do not have editable text or a
        // PhotoViewer-compatible raster source. Go directly to the same
        // scrolling recipient grid as Envio múltiplo, without a composer.
        if (sources.size() == 1 && isAnimatedStickerMedia(sources.get(0))) {
            MessageObject sticker = sources.get(0);
            if (!canOfferSingleMedia(sticker)) return;
            openAnimatedStickerRecipients(host, sticker);
            return;
        }

        // Pro Forward: compose captions directly in the source chat instead
        // of opening a modal PhotoViewer or requiring local gallery saves.
        // Telegram can reuse the source photo/document media reference.
        host.startUltraTelMediaCompose(sources);
        return;


    }

    /**
     * Sends a new media message using Telegram's existing remote media
     * references; it does not use messages.forwardMessages and does not
     * require the photo/video to be downloaded or saved to the gallery.
     * The source MessageObject is treated as read-only.
     */
    public static void openRemoteMediaRecipientPicker(ChatActivity host,
            ArrayList<MessageObject> sources, CharSequence caption,
            Runnable onSent) {
        if (host == null || host.getParentActivity() == null
                || sources == null || sources.isEmpty()) return;

        final ArrayList<MessageObject> safeSources = new ArrayList<>(sources);
        for (MessageObject src : safeSources) {
            if (!canOfferSingleMedia(src)) return;
            TLRPC.MessageMedia media = MessageObject.getMedia(src.messageOwner);
            if (!(media != null && media.photo instanceof TLRPC.TL_photo)
                    && !(src.getDocument() instanceof TLRPC.TL_document)) {
                Toast.makeText(host.getParentActivity(),
                        "Esta mídia não está disponível para envio como nova mensagem.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }

        ShareAlert picker = new ShareAlert(host.getParentActivity(),
                (ArrayList<MessageObject>) null, "", false, null, false);
        picker.setUltraTelEditedMediaRecipientCallback((dialogIds, withSound, pickerComment) -> {
            String captionText = caption == null ? "" : caption.toString();
            // A recipient-panel comment supplements the caption rather
            // than silently replacing the edits made in the source chat.
            if (!TextUtils.isEmpty(pickerComment)) {
                captionText = captionText.isEmpty() ? pickerComment.toString()
                        : captionText + "\n" + pickerComment;
            }
            return sendRemoteMedia(host, safeSources, captionText,
                    dialogIds, withSound, onSent);
        });
        host.showDialog(picker);
    }

    private static boolean sendRemoteMedia(ChatActivity host,
            ArrayList<MessageObject> sources, String caption,
            ArrayList<Long> dialogIds, boolean withSound, Runnable onSent) {
        if (host.getParentActivity() == null || dialogIds == null
                || !UltraTelBulkSendGuard.isWithinRecipientLimit(dialogIds.size())) {
            return false;
        }
        final ArrayList<Long> paidDialogs = new ArrayList<>();
        int paidMessageCount = 0;
        for (Long did : dialogIds) {
            if (did == null || !UltraTelBulkSendGuard.isEligibleRecipient(did)) {
                Toast.makeText(host.getParentActivity(),
                        "Selecione apenas conversas privadas.", Toast.LENGTH_SHORT).show();
                return false;
            }
            long price = host.getMessagesController().getSendPaidMessagesStars(did);
            if (price <= 0) {
                price = DialogObject.getMessagesStarsPrice(
                        host.getMessagesController().isUserContactBlocked(did));
            }
            if (price > 0) {
                paidDialogs.add(did);
                paidMessageCount += sources.size();
            }
        }

        final String editedCaption = caption == null ? "" : caption;
        AlertsCreator.ensurePaidMessagesMultiConfirmation(
                host.getCurrentAccount(), paidDialogs, paidMessageCount, prices -> {
                    for (Long did : dialogIds) {
                        Long stars = prices == null ? null : prices.get(did);
                        long payStars = stars == null ? 0L : stars;
                        for (int index = 0; index < sources.size(); index++) {
                            MessageObject src = sources.get(index);
                            TLRPC.MessageMedia media = MessageObject.getMedia(src.messageOwner);
                            String itemCaption = index == 0 ? editedCaption :
                                    (src.messageOwner.message == null ? "" : src.messageOwner.message);
                            CharSequence[] entityText = { itemCaption };
                            ArrayList<TLRPC.MessageEntity> entities =
                                    MediaDataController.getInstance(host.getCurrentAccount())
                                            .getEntities(entityText, true);
                            String finalText = entityText[0] == null ? "" : entityText[0].toString();
                            SendMessagesHelper.SendMessageParams params;
                            if (media != null && media.photo instanceof TLRPC.TL_photo) {
                                params = SendMessagesHelper.SendMessageParams.of(
                                        (TLRPC.TL_photo) media.photo, null, did,
                                        null, null, finalText, entities,
                                        null, null, withSound, 0, 0, 0, src, false);
                            } else if (src.getDocument() instanceof TLRPC.TL_document) {
                                params = SendMessagesHelper.SendMessageParams.of(
                                        (TLRPC.TL_document) src.getDocument(), null, null,
                                        did, null, null, finalText, entities,
                                        null, null, withSound, 0, 0, 0, src, null, false);
                            } else {
                                continue;
                            }
                            params.payStars = payStars;
                            host.getAccountInstance().getSendMessagesHelper().sendMessage(params);
                        }
                    }
                    if (onSent != null) onSent.run();
                });
        return true;
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

    /**
     * Animated stickers are resent as native Telegram stickers (not forwarded
     * messages, converted videos or generic documents).
     */
    private static void openAnimatedStickerRecipients(ChatActivity host, MessageObject source) {
        if (host.getParentActivity() == null || !(source.getDocument() instanceof TLRPC.TL_document)) {
            return;
        }
        ShareAlert picker = new ShareAlert(host.getParentActivity(),
                (ArrayList<MessageObject>) null, "", false, null, false);
        picker.setUltraTelEditedMediaRecipientCallback(
                (dialogIds, withSound, unusedCaption) ->
                        sendAnimatedSticker(host, source, dialogIds, withSound),
                true);
        host.showDialog(picker);
    }

    private static boolean sendAnimatedSticker(ChatActivity host, MessageObject source,
            ArrayList<Long> dialogIds, boolean withSound) {
        if (host.getParentActivity() == null || source == null
                || source.messageOwner == null || source.messageOwner.noforwards
                || !source.canForwardMessage()
                || !(source.getDocument() instanceof TLRPC.TL_document)
                || dialogIds == null
                || !UltraTelBulkSendGuard.isWithinRecipientLimit(dialogIds.size())) {
            return false;
        }

        ArrayList<Long> paidDialogs = new ArrayList<>();
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
                paidMessages++;
            }
        }

        final TLRPC.Document stickerDocument = source.getDocument();
        AlertsCreator.ensurePaidMessagesMultiConfirmation(
                host.getCurrentAccount(), paidDialogs, paidMessages, prices -> {
                    for (Long did : dialogIds) {
                        Long price = prices == null ? null : prices.get(did);
                        host.getAccountInstance().getSendMessagesHelper().sendSticker(
                                stickerDocument, null, did,
                                null, null, null, null, null,
                                withSound, 0, 0, false, source, null,
                                price == null ? 0L : price, 0, null);
                    }
                });
        return true;
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
        picker.setUltraTelEditedMediaRecipientCallback((dialogIds, withSound, pickerCaption) -> {
            CharSequence caption = !TextUtils.isEmpty(pickerCaption)
                    ? pickerCaption : initialCaption;
            return dispatchEditedMedia(host, entries, caption,
                    sendAsDocumentFromEditor, dialogIds, withSound);
        });
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
