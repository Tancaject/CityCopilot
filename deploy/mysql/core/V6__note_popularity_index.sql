-- queryHotBlog orders by liked DESC, id DESC; the author/feed index serves a different access path.
ALTER TABLE note ADD INDEX idx_note_popularity (liked, id);
