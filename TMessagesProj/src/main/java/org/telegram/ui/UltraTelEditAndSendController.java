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
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * UltraTel Beta's Editar e Enviar flow, modeled on Turbo Tel's ForwardEditing:
 * edit the caption in the chat input, then select recipients in ShareAlert.
 *
 * Original messages are never modified. New outgoing messages use Telegram's
 * remote photo/document references, so the feature needs no gallery save,
 * separate PhotoViewer modal, or duplicate local download.
 */
public final class UltraTelEditAndSendController {
    // Each new Editar e Enviar operation invalidates pickers from older drafts.
    private static final Map<ChatActivity, Long> pickerSessions = new WeakHashMap<>();

    private static synchronized long nextPickerSession(ChatActivity host) {
        long next = pickerSessions.getOrDefault(host, 0L) + 1;
        pickerSessions.put(host, next);
        return next;
    }

    private static synchronized boolean isCurrentPickerSession(ChatActivity host, long session) {
        return host != null && host.getParentActivity() != null
                && pickerSessions.getOrDefault(host, 0L) == session;
    }

    private static synchronized long currentPickerSession(ChatActivity host) {
        return pickerSessions.getOrDefault(host, 0L);
    }

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
        return canOfferSingleMedia(source) || isEditableTextMessage(source);
    }

    /** Text and link-preview messages use the same editor and recipient picker. */
    public static boolean isEditableTextMessage(MessageObject source) {
        return source != null && source.messageOwner != null
                && source.type == MessageObject.TYPE_TEXT
                && source.messageOwner.action == null
                && !source.messageOwner.noforwards
                && source.canForwardMessage()
                && !TextUtils.isEmpty(source.messageOwner.message);
    }

    private static boolean canOfferSingleMedia(MessageObject source) {
        return source != null && source.messageOwner != null
                && source.messageOwner.action == null
                && !source.messageOwner.noforwards && source.canForwardMessage()
                && (source.isPhoto() || source.isVideo() || source.isGif()
                    || source.isSticker() || source.isAnimatedSticker() || source.isVideoSticker()
                    || source.getDocument() instanceof TLRPC.TL_document);
    }

    /**
     * Turbo Tel ForwardEditing (action 400) flow: compose the caption inside
     * the original chat, then pick destinations. No PhotoViewer popup and no
     * local media download are needed for this caption-only edit.
     */
    public static void open(ChatActivity host, ArrayList<MessageObject> sources) {
        if (!BuildVars.isBetaApp() || host == null || host.getParentActivity() == null
                || sources == null || sources.isEmpty()) return;
        nextPickerSession(host);
        final ArrayList<MessageObject> safeSources = new ArrayList<>(sources);
        for (MessageObject source : safeSources) {
            if (!canOfferSingleMedia(source) && !isEditableTextMessage(source)) return;
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

        final long pickerSession = currentPickerSession(host);
        ShareAlert picker = new ShareAlert(host.getParentActivity(),
                (ArrayList<MessageObject>) null, "", false, null, false);
        picker.setUltraTelEditedMediaRecipientCallback((dialogIds, topics, withSound,
                pickerComment, includeText, includeQuote) -> {
            if (!isCurrentPickerSession(host, pickerSession)) return false;
            CharSequence edited = includeText ? (caption == null ? "" : caption) : "";
            if (includeText && !TextUtils.isEmpty(pickerComment)) {
                edited = TextUtils.isEmpty(edited) ? pickerComment
                        : TextUtils.concat(edited, "\n", pickerComment);
            }
            return sendRemoteMedia(host, safeSources, edited, dialogIds, topics,
                    withSound, onSent, includeText, includeQuote, pickerSession);
        }, skipCaption);
        host.showDialog(picker);
    }

    /**
     * Edit a forwarded text message (including messages with web previews)
     * in the same native composer. Interactive bot keyboards remain owned
     * by the bot and are not copied into user-created messages.
     */
    public static void openTextRecipientPicker(ChatActivity host, MessageObject source,
            CharSequence editedText, Runnable onSent) {
        if (host == null || host.getParentActivity() == null
                || !isEditableTextMessage(source)) return;

        final long pickerSession = currentPickerSession(host);
        ShareAlert picker = new ShareAlert(host.getParentActivity(),
                (ArrayList<MessageObject>) null, "", false, null, false);
        picker.setUltraTelEditedMediaRecipientCallback((dialogIds, topics, withSound,
                pickerComment, includeText, includeQuote) -> {
            if (!isCurrentPickerSession(host, pickerSession)) return false;
            CharSequence outgoing = includeText ? (editedText == null ? "" : editedText) : "";
            if (includeText && !TextUtils.isEmpty(pickerComment)) {
                outgoing = TextUtils.isEmpty(outgoing) ? pickerComment
                        : TextUtils.concat(outgoing, "\n", pickerComment);
            }
            if (!includeQuote && TextUtils.isEmpty(outgoing)) {
                Toast.makeText(host.getParentActivity(),
                        "Ative Texto ou Citar para enviar esta mensagem.",
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            return sendEditedText(host, source, outgoing, dialogIds, topics,
                    withSound, includeText, includeQuote, onSent, pickerSession);
        });
        host.showDialog(picker);
    }

    private static boolean sendEditedText(ChatActivity host, MessageObject source,
            CharSequence outgoingText, ArrayList<Long> dialogIds,
            Map<Long, TLRPC.TL_forumTopic> topics, boolean withSound,
            boolean includeText, boolean includeQuote, Runnable onSent, long pickerSession) {
        if (!isCurrentPickerSession(host, pickerSession)) return false;
        if (host.getParentActivity() == null || dialogIds == null || dialogIds.isEmpty()) {
            return false;
        }
        String original = source.messageOwner.message == null ? "" : source.messageOwner.message;
        if (includeQuote && includeText && !TextUtils.equals(
                outgoingText == null ? "" : outgoingText.toString(), original)) {
            Toast.makeText(host.getParentActivity(),
                    "Para enviar o texto editado, desmarque Citar.",
                    Toast.LENGTH_LONG).show();
            return false;
        }

        ArrayList<Long> paidDialogs = new ArrayList<>();
        for (Long did : dialogIds) {
            if (did == null || !isValidDestination(host, did)) {
                Toast.makeText(host.getParentActivity(),
                        "O Telegram não permite enviar para este destino.",
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            long price = host.getMessagesController().getSendPaidMessagesStars(did);
            if (price <= 0) price = DialogObject.getMessagesStarsPrice(
                    host.getMessagesController().isUserContactBlocked(did));
            if (price > 0) paidDialogs.add(did);
        }
        AlertsCreator.ensurePaidMessagesMultiConfirmation(host.getCurrentAccount(),
                paidDialogs, paidDialogs.size(), prices -> {
                    if (!isCurrentPickerSession(host, pickerSession)) return;
                    for (Long did : dialogIds) {
                        Long stars = prices == null ? null : prices.get(did);
                        long payStars = stars == null ? 0 : stars;
                        TLRPC.TL_forumTopic topic = topics == null ? null : topics.get(did);
                        boolean mono = topic != null && host.getMessagesController().isMonoForum(did);
                        MessageObject replyTop = topic == null || mono ? null
                                : new MessageObject(host.getCurrentAccount(),
                                        topic.topicStartMessage, false, false);
                        long monoPeer = mono ? DialogObject.getPeerDialogId(topic.from_id) : 0;
                        if (includeQuote) {
                            ArrayList<MessageObject> originals = new ArrayList<>();
                            originals.add(source);
                            host.getAccountInstance().getSendMessagesHelper().sendMessage(
                                    originals, did, false, !includeText, withSound,
                                    0, replyTop, -1, payStars);
                        } else {
                            CharSequence[] entityText = {outgoingText};
                            ArrayList<TLRPC.MessageEntity> entities =
                                    MediaDataController.getInstance(host.getCurrentAccount())
                                            .getEntities(entityText, true);
                            String text = entityText[0] == null ? "" : entityText[0].toString();
                            SendMessagesHelper.SendMessageParams params =
                                    SendMessagesHelper.SendMessageParams.of(
                                            text, did, null, replyTop, null, true,
                                            entities, null, null, withSound, 0, 0,
                                            null, false);
                            params.payStars = payStars;
                            params.monoForumPeer = monoPeer;
                            host.getAccountInstance().getSendMessagesHelper().sendMessage(params);
                        }
                    }
                    if (onSent != null) onSent.run();
                });
        return true;
    }

    private static boolean isValidDestination(ChatActivity host, long did) {
        if (did == 0 || did == Long.MAX_VALUE || DialogObject.isEncryptedDialog(did)) return false;
        if (DialogObject.isUserDialog(did)) return true;
        if (!DialogObject.isChatDialog(did)) return false;
        TLRPC.Chat chat = host.getMessagesController().getChat(-did);
        return chat != null && !org.telegram.messenger.ChatObject.isNotInChat(chat)
                && (!org.telegram.messenger.ChatObject.isChannel(chat)
                    || chat.megagroup
                    || org.telegram.messenger.ChatObject.isCanWriteToChannel(
                            -did, host.getCurrentAccount()));
    }

    private static boolean sendRemoteMedia(ChatActivity host,
            ArrayList<MessageObject> sources, CharSequence caption,
            ArrayList<Long> dialogIds, Map<Long, TLRPC.TL_forumTopic> topics,
            boolean withSound, Runnable onSent, boolean includeText, boolean includeQuote,
            long pickerSession) {
        if (!isCurrentPickerSession(host, pickerSession)) return false;
        if (host.getParentActivity() == null || dialogIds == null || dialogIds.isEmpty()) return false;

        String originalCaption = "";
        for (MessageObject source : sources) {
            if (!TextUtils.isEmpty(source.messageOwner.message)) {
                originalCaption = source.messageOwner.message;
                break;
            }
        }
        if (includeQuote && includeText && !TextUtils.equals(
                caption == null ? "" : caption.toString(), originalCaption)) {
            Toast.makeText(host.getParentActivity(),
                    "Para manter a citação, mantenha a legenda original ou desmarque Citação.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        final ArrayList<Long> paidDialogs = new ArrayList<>();
        int paidMessages = 0;
        for (Long did : dialogIds) {
            if (did == null || !isValidDestination(host, did)) {
                Toast.makeText(host.getParentActivity(),
                        "Selecione uma conversa na qual você pode enviar mídia.",
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            long price = host.getMessagesController().getSendPaidMessagesStars(did);
            if (price <= 0) price = DialogObject.getMessagesStarsPrice(
                    host.getMessagesController().isUserContactBlocked(did));
            if (price > 0) {
                paidDialogs.add(did);
                paidMessages += sources.size();
            }
        }

        final CharSequence outgoingCaption = caption == null ? "" : caption;
        AlertsCreator.ensurePaidMessagesMultiConfirmation(
                host.getCurrentAccount(), paidDialogs, paidMessages, prices -> {
                    if (!isCurrentPickerSession(host, pickerSession)) return;
                    for (Long did : dialogIds) {
                        Long stars = prices == null ? null : prices.get(did);
                        long payStars = stars == null ? 0L : stars;
                        TLRPC.TL_forumTopic topic = topics == null ? null : topics.get(did);
                        boolean mono = topic != null && host.getMessagesController().isMonoForum(did);
                        MessageObject replyTop = topic == null || mono ? null
                                : new MessageObject(host.getCurrentAccount(),
                                        topic.topicStartMessage, false, false);
                        long monoPeer = mono ? DialogObject.getPeerDialogId(topic.from_id) : 0;
                        if (includeQuote) {
                            // Native forwarding preserves attribution and native album grouping.
                            host.getAccountInstance().getSendMessagesHelper().sendMessage(
                                    new ArrayList<>(sources), did, false, !includeText,
                                    withSound, 0, replyTop, -1, payStars);
                            continue;
                        }

                        long sourceGroup = sources.size() > 1 ? sources.get(0).getGroupId() : 0;
                        boolean album = sourceGroup != 0;
                        for (MessageObject src : sources) {
                            if (src.getGroupId() != sourceGroup || src.isSticker()
                                    || src.isAnimatedSticker() || src.isGif()) album = false;
                        }
                        long groupId = album
                                ? host.getAccountInstance().getSendMessagesHelper().getNextRandomId() : 0;
                        for (int i = 0; i < sources.size(); i++) {
                            MessageObject src = sources.get(i);
                            CharSequence itemCaption = i == 0 ? outgoingCaption
                                    : (album || !includeText ? ""
                                       : (src.messageOwner.message == null ? "" : src.messageOwner.message));
                            CharSequence[] entityText = {itemCaption};
                            ArrayList<TLRPC.MessageEntity> entities =
                                    MediaDataController.getInstance(host.getCurrentAccount())
                                            .getEntities(entityText, true);
                            String text = entityText[0] == null ? "" : entityText[0].toString();
                            SendMessagesHelper.SendMessageParams params;
                            if (src.getPhoto() instanceof TLRPC.TL_photo) {
                                params = SendMessagesHelper.SendMessageParams.of(
                                        (TLRPC.TL_photo) src.getPhoto(), null, did, null,
                                        replyTop, text, entities, null, null,
                                        withSound, 0, 0, 0, src, false);
                            } else if (src.getDocument() instanceof TLRPC.TL_document) {
                                params = SendMessagesHelper.SendMessageParams.of(
                                        (TLRPC.TL_document) src.getDocument(), null, null, did,
                                        null, replyTop, text, entities, null, null,
                                        withSound, 0, 0, 0, src, null, false);
                            } else {
                                continue;
                            }
                            if (album) {
                                if (i > 0 && i % 10 == 0) {
                                    groupId = host.getAccountInstance().getSendMessagesHelper().getNextRandomId();
                                }
                                params.params = new HashMap<>();
                                params.params.put("groupId", Long.toString(groupId));
                                if (i == sources.size() - 1 || i % 10 == 9) {
                                    params.params.put("final", "1");
                                }
                            }
                            params.payStars = payStars;
                            params.monoForumPeer = monoPeer;
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
        final long pickerSession = currentPickerSession(host);
        ShareAlert picker = new ShareAlert(host.getParentActivity(),
                (ArrayList<MessageObject>) null, "", false, null, false);
        picker.setUltraTelEditedMediaRecipientCallback(
                (dialogIds, topics, withSound, unusedCaption, includeText, includeQuote) ->
                        isCurrentPickerSession(host, pickerSession) &&
                        sendAnimatedSticker(host, source, dialogIds, withSound, includeQuote, pickerSession),
                true);
        host.showDialog(picker);
    }

    private static boolean sendAnimatedSticker(ChatActivity host, MessageObject source,
            ArrayList<Long> dialogIds, boolean withSound, boolean includeQuote,
            long pickerSession) {
        if (!isCurrentPickerSession(host, pickerSession)) return false;
        if (host.getParentActivity() == null || source == null
                || source.messageOwner == null || source.messageOwner.noforwards
                || !source.canForwardMessage()
                || !(source.getDocument() instanceof TLRPC.TL_document)
                || dialogIds == null || dialogIds.isEmpty()) return false;

        ArrayList<Long> paidDialogs = new ArrayList<>();
        int paidMessages = 0;
        for (Long did : dialogIds) {
            if (did == null || !isValidDestination(host, did)) {
                Toast.makeText(host.getParentActivity(),
                        "Selecione uma conversa na qual você pode enviar mídia.",
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            MessagesController controller = host.getMessagesController();
            long price = controller.getSendPaidMessagesStars(did);
            if (price <= 0) price = DialogObject.getMessagesStarsPrice(
                    controller.isUserContactBlocked(did));
            if (price > 0) {
                paidDialogs.add(did);
                paidMessages++;
            }
        }
        final TLRPC.Document stickerDocument = source.getDocument();
        AlertsCreator.ensurePaidMessagesMultiConfirmation(
                host.getCurrentAccount(), paidDialogs, paidMessages, prices -> {
                    if (!isCurrentPickerSession(host, pickerSession)) return;
                    for (Long did : dialogIds) {
                        Long stars = prices == null ? null : prices.get(did);
                        long payStars = stars == null ? 0L : stars;
                        if (includeQuote) {
                            ArrayList<MessageObject> messages = new ArrayList<>();
                            messages.add(source);
                            host.getAccountInstance().getSendMessagesHelper().sendMessage(
                                    messages, did, false, false, withSound, 0, payStars);
                        } else {
                            host.getAccountInstance().getSendMessagesHelper().sendSticker(
                                    stickerDocument, null, did, null, null, null, null, null,
                                    withSound, 0, 0, false, source, null, payStars, 0, null);
                        }
                    }
                });
        return true;
    }
}
