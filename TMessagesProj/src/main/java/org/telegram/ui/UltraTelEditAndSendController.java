package org.telegram.ui;

import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UltraTelBulkSendGuard;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ShareAlert;

import java.util.ArrayList;

/**
 * UltraTel Beta's Editar e Enviar flow, modeled on Turbo Tel's ForwardEditing:
 * edit the caption in the chat input, then select recipients in ShareAlert.
 *
 * Original messages are never modified. New outgoing messages use Telegram's
 * remote photo/document references, so the feature needs no gallery save,
 * separate PhotoViewer modal, or duplicate local download.
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
            CharSequence captionText = caption == null ? "" : caption;
            // Retain styled spans from the chat composer. A recipient-panel
            // comment is appended instead of replacing the original edits.
            if (!TextUtils.isEmpty(pickerComment)) {
                captionText = TextUtils.isEmpty(captionText) ? pickerComment
                        : TextUtils.concat(captionText, "\n", pickerComment);
            }
            return sendRemoteMedia(host, safeSources, captionText,
                    dialogIds, withSound, onSent);
        }, skipCaption);
        host.showDialog(picker);
    }

    private static boolean sendRemoteMedia(ChatActivity host,
            ArrayList<MessageObject> sources, CharSequence caption,
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

        final CharSequence editedCaption = caption == null ? "" : caption;
        AlertsCreator.ensurePaidMessagesMultiConfirmation(
                host.getCurrentAccount(), paidDialogs, paidMessageCount, prices -> {
                    for (Long did : dialogIds) {
                        Long stars = prices == null ? null : prices.get(did);
                        long payStars = stars == null ? 0L : stars;
                        for (int index = 0; index < sources.size(); index++) {
                            MessageObject src = sources.get(index);
                            CharSequence itemCaption = index == 0 ? editedCaption :
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


}
