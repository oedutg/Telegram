package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local, non-mutating source snapshot for UltraTel's Edit and Send composer.
 * No message objects or Telegram media descriptors are modified here.
 * A missing local file must be downloaded before a media editor is opened.
 */
public final class UltraTelEditAndSendDraft {
    public enum Kind { TEXT, PHOTO, VIDEO, GIF, STICKER, ALBUM, UNSUPPORTED }

    public static final class Item {
        public final Kind kind;
        public final String caption;
        public final File localFile;

        private Item(Kind kind, String caption, File localFile) {
            this.kind = kind;
            this.caption = caption;
            this.localFile = localFile;
        }

        public boolean isFileReady() {
            return localFile != null && localFile.isFile();
        }
    }

    public final Kind kind;
    public final List<Item> items;

    private UltraTelEditAndSendDraft(Kind kind, List<Item> items) {
        this.kind = kind;
        this.items = Collections.unmodifiableList(items);
    }

    public static UltraTelEditAndSendDraft fromMessages(int account, List<MessageObject> sources) {
        if (sources == null || sources.isEmpty()) return null;
        ArrayList<Item> copied = new ArrayList<>();
        for (MessageObject source : sources) {
            if (source == null || source.messageOwner == null
                    || source.messageOwner.noforwards || !source.canForwardMessage()
                    || source.messageOwner.action != null) return null;
            Kind type = classify(source);
            if (type == Kind.UNSUPPORTED) return null;
            File file = null;
            if (type != Kind.TEXT) {
                String attachPath = source.messageOwner.attachPath;
                if (attachPath != null && !attachPath.isEmpty()) {
                    File candidate = new File(attachPath);
                    if (candidate.isFile()) file = candidate;
                }
                if (file == null) {
                    File candidate = FileLoader.getInstance(account).getPathToMessage(source.messageOwner);
                    if (candidate != null && candidate.isFile()) file = candidate;
                }
            }
            copied.add(new Item(type,
                    source.messageOwner.message == null ? "" : source.messageOwner.message,
                    file));
        }
        Kind overall = copied.size() > 1 ? Kind.ALBUM : copied.get(0).kind;
        return new UltraTelEditAndSendDraft(overall, copied);
    }

    private static Kind classify(MessageObject source) {
        if (source.isSticker()) return Kind.STICKER;
        if (source.isGif()) return Kind.GIF;
        if (source.isVideo()) return Kind.VIDEO;
        if (source.isPhoto()) return Kind.PHOTO;
        if (source.type == 0 && source.messageOwner.media == null) return Kind.TEXT;
        return Kind.UNSUPPORTED;
    }

    public boolean allMediaFilesReady() {
        for (Item item : items) {
            if (item.kind != Kind.TEXT && !item.isFileReady()) return false;
        }
        return true;
    }
}
