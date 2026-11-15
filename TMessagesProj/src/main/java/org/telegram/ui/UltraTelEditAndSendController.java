package org.telegram.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.NotificationCenter;
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
import java.util.HashSet;
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

    /**
     * Turbo Tel ForwardEditing (action 400) flow: compose the caption inside
     * the original chat, then pick destinations. No PhotoViewer popup and no
     * local media download are needed for this caption-only edit.
     */
    public static void open(ChatActivity host, ArrayList<MessageObject> sources) {
        if (!BuildVars.isBetaApp() || host == null || host.getParentActivity() == null
                || sources == null || sources.isEmpty()) return;
        final ArrayList<MessageObject> safeSources = new ArrayList<>(sources);
        for (MessageObject source : safeSources) {
            if (!canOfferSingleMedia(source)) return;
        }

        // Stickers and GIFs go immediately to recipients, without requiring
        // a blank caption or opening the chat keyboard.
        if (safeSources.size() == 1) {
            MessageObject source = safeSources.get(0);
            if (source.isSticker() || source.isAnimatedSticker() || source.isVideoSticker()) {
                openAnimatedStickerRecipients(host, source);
                return;
            }
            if (source.isGif()) {
                openRemoteMediaRecipientPicker(host, safeSources, null, null, true);
                return;
            }
        }
        host.startUltraTelMediaCompose(safeSources);
    }

    private static void openDraftEditor(ChatActivity host, UltraTelMediaDraft draft) {
        if (host.getParentActivity() == null || draft == null || draft.getItems().isEmpty()) return;
        ArrayList<MediaController.PhotoEntry> entries = new ArrayList<>();
        int index = 0;
        for (UltraTelMediaDraft.Item item : draft.getItems()) {
            File localFile = item.file;
            if (localFile == null || !localFile.isFile()) {
                Toast.makeText(host.getParentActivity(),
                        "O arquivo ainda não está disponível para edição.", Toast.LENGTH_SHORT).show();
                return;
            }
            // GIFs are MP4-backed animations and use the video editor.
            boolean video = item.video || item.gif;
            MediaController.PhotoEntry entry = new MediaController.PhotoEntry(
                    -1, -1 - index, System.currentTimeMillis() / 1000,
                    localFile.getAbsolutePath(), 0, video, 0, 0, localFile.length());
            entry.caption = item.caption;
            entry.entities = item.source.messageOwner.entities;
            entry.canDeleteAfter = false;
            entries.add(entry);
            index++;
        }
        openMediaEditor(host, entries, entries.get(0).caption);
    }

    /**
     * PhotoViewer requires an on-device file, not a remote Telegram media
     * reference. Download missing files into Telegram's cache, then reopen the
     * editor. Never save the originals to the public photo gallery.
     */
    private static final class PendingMediaDownload
            implements NotificationCenter.NotificationCenterDelegate {
        private final ChatActivity host;
        private final ArrayList<MessageObject> sources;
        private final int account;
        private final HashSet<String> pending = new HashSet<>();
        private boolean finished;

        PendingMediaDownload(ChatActivity host, ArrayList<MessageObject> sources) {
            this.host = host;
            this.sources = sources;
            this.account = host.getCurrentAccount();
        }

        void start() {
            ArrayList<Runnable> requests = new ArrayList<>();
            FileLoader loader = FileLoader.getInstance(account);
            for (MessageObject source : sources) {
                if (isLocallyAvailable(source, account)) continue;
                if (source.getDocument() instanceof TLRPC.TL_document) {
                    TLRPC.Document document = source.getDocument();
                    String name = FileLoader.getAttachFileName(document);
                    if (TextUtils.isEmpty(name)) {
                        fail(); return;
                    }
                    pending.add(name);
                    requests.add(() -> loader.loadFile(
                            document, source, FileLoader.PRIORITY_HIGH, 0));
                } else if (source.getPhoto() instanceof TLRPC.TL_photo) {
                    TLRPC.Photo photo = source.getPhoto();
                    TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(
                            photo.sizes, AndroidUtilities.getPhotoSize());
                    ImageLocation location = ImageLocation.getForPhoto(size, photo);
                    if (location == null) {
                        fail(); return;
                    }
                    String name = FileLoader.getAttachFileName(size);
                    if (TextUtils.isEmpty(name)) {
                        fail(); return;
                    }
                    pending.add(name);
                    requests.add(() -> loader.loadFile(
                            location, source, "jpg", FileLoader.PRIORITY_HIGH, 0));
                } else {
                    fail(); return;
                }
            }
            if (pending.isEmpty()) {
                showEditorIfReady();
                return;
            }
            NotificationCenter notifications = NotificationCenter.getInstance(account);
            notifications.addObserver(this, NotificationCenter.fileLoaded);
            notifications.addObserver(this, NotificationCenter.fileLoadFailed);
            if (host.getParentActivity() != null) {
                Toast.makeText(host.getParentActivity(),
                        "Baixando mídia para edição…", Toast.LENGTH_SHORT).show();
            }
            for (Runnable request : requests) request.run();
        }

        private static boolean isLocallyAvailable(MessageObject source, int account) {
            if (source.messageOwner.attachPath != null) {
                File attached = new File(source.messageOwner.attachPath);
                if (attached.isFile()) return true;
            }
            FileLoader loader = FileLoader.getInstance(account);
            File path = loader.getPathToMessage(source.messageOwner, false, true);
            if (path != null && path.isFile()) return true;
            path = loader.getPathToMessage(source.messageOwner, true, true);
            return path != null && path.isFile();
        }

        @Override
        public void didReceivedNotification(int id, int eventAccount, Object... args) {
            if (finished || eventAccount != account || args.length == 0
                    || !(args[0] instanceof String) || !pending.contains(args[0])) return;
            if (id == NotificationCenter.fileLoadFailed) {
                fail();
            } else if (id == NotificationCenter.fileLoaded) {
                pending.remove((String) args[0]);
                if (pending.isEmpty()) {
                    cleanup();
                    showEditorIfReady();
                }
            }
        }

        private void showEditorIfReady() {
            if (host.getParentActivity() == null) return;
            UltraTelMediaDraft draft = UltraTelMediaDraft.fromMessages(sources, account);
            if (draft == null) {
                Toast.makeText(host.getParentActivity(),
                        "Não foi possível preparar a mídia para edição.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            openDraftEditor(host, draft);
        }

        private void fail() {
            cleanup();
            if (host.getParentActivity() != null) {
                Toast.makeText(host.getParentActivity(),
                        "Falha ao baixar a mídia original para edição.",
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void cleanup() {
            if (finished) return;
            finished = true;
            NotificationCenter notifications = NotificationCenter.getInstance(account);
            notifications.removeObserver(this, NotificationCenter.fileLoaded);
            notifications.removeObserver(this, NotificationCenter.fileLoadFailed);
        }
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
        openRemoteMediaRecipientPicker(host, sources, caption, onSent, false);
    }

    private static void openRemoteMediaRecipientPicker(ChatActivity host,
            ArrayList<MessageObject> sources, CharSequence caption,
            Runnable onSent, boolean skipCaption) {
        if (host == null || host.getParentActivity() == null
                || sources == null || sources.isEmpty()) return;

        final ArrayList<MessageObject> safeSources = new ArrayList<>(sources);
        for (MessageObject src : safeSources) {
            if (!canOfferSingleMedia(src)) return;
            // getPhoto() resolves ordinary photos, webpage previews and
            // rich-message photos. Looking only at media.photo loses them.
            if (!(src.getPhoto() instanceof TLRPC.TL_photo)
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
        }, skipCaption);
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
                            String itemCaption = index == 0 ? editedCaption :
                                    (src.messageOwner.message == null ? "" : src.messageOwner.message);
                            CharSequence[] entityText = { itemCaption };
                            ArrayList<TLRPC.MessageEntity> entities =
                                    MediaDataController.getInstance(host.getCurrentAccount())
                                            .getEntities(entityText, true);
                            String finalText = entityText[0] == null ? "" : entityText[0].toString();
                            SendMessagesHelper.SendMessageParams params;
                            if (src.getPhoto() instanceof TLRPC.TL_photo) {
                                params = SendMessagesHelper.SendMessageParams.of(
                                        (TLRPC.TL_photo) src.getPhoto(), null, did,
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
