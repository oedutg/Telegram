package org.telegram.messenger;

import org.telegram.messenger.FileLoader;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local, non-destructive media draft for UltraTel Beta Edit and Send.
 *
 * This is deliberately not a forward operation. All files must be available
 * locally before opening the Telegram media editor. The editor and sending
 * pipeline must consume this draft; merely forwarding the source message is
 * not a valid fallback.
 */
public final class UltraTelMediaDraft {
    public static final class Item {
        public final MessageObject source;
        public final File file;
        public final boolean video;
        public final boolean gif;
        public final boolean sticker;
        public final String caption;

        private Item(MessageObject source, File file) {
            this.source = source;
            this.file = file;
            this.video = source.isVideo();
            this.gif = source.isGif();
            this.sticker = source.isSticker();
            this.caption = source.messageOwner.message == null ? "" : source.messageOwner.message;
        }
    }

    private final List<Item> items;

    private UltraTelMediaDraft(List<Item> items) {
        this.items = Collections.unmodifiableList(items);
    }

    public List<Item> getItems() {
        return items;
    }

    public boolean isAlbum() {
        return items.size() > 1;
    }

    /**
     * Returns null if a file is unavailable or the source is protected.
     * No download, mutation or message dispatch occurs here.
     */
    public static UltraTelMediaDraft fromMessages(List<MessageObject> sources, int account) {
        if (!BuildVars.isBetaApp() || sources == null || sources.isEmpty()) {
            return null;
        }
        List<Item> result = new ArrayList<>();
        long groupId = 0;
        for (MessageObject source : sources) {
            if (source == null || source.messageOwner == null
                    || source.messageOwner.noforwards || !source.canForwardMessage()
                    || !(source.isPhoto() || source.isVideo() || source.isGif() || source.isSticker())) {
                return null;
            }
            if (sources.size() > 1) {
                if (source.getGroupId() == 0) return null;
                if (groupId == 0) groupId = source.getGroupId();
                if (groupId != source.getGroupId()) return null;
            }
            File file = null;
            if (source.messageOwner.attachPath != null && !source.messageOwner.attachPath.isEmpty()) {
                File attached = new File(source.messageOwner.attachPath);
                if (attached.isFile()) file = attached;
            }
            if (file == null) {
                file = FileLoader.getInstance(account).getPathToMessage(source.messageOwner, false, true);
            }
            if (file == null || !file.isFile()) {
                file = FileLoader.getInstance(account).getPathToMessage(source.messageOwner, true, true);
            }
            if (file == null || !file.isFile()) {
                return null;
            }
            result.add(new Item(source, file));
        }
        return new UltraTelMediaDraft(result);
    }
}
