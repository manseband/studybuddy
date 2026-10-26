-- StudyBuddy SQLite schema (version 1).
-- Child rows are removed with their course (ON DELETE CASCADE). When a concept is deleted, questions and
-- flashcards that referenced it become untagged (ON DELETE SET NULL), as required by F03.
-- Lists and small nested structures are stored as JSON text.

CREATE TABLE IF NOT EXISTS courses (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    name      TEXT NOT NULL UNIQUE COLLATE NOCASE,
    code      TEXT NOT NULL DEFAULT '',
    exam_date TEXT
);

CREATE TABLE IF NOT EXISTS materials (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    course_id    INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    title        TEXT NOT NULL,
    file_type    TEXT NOT NULL,
    status       TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    page_count   INTEGER NOT NULL DEFAULT 0,
    word_count   INTEGER NOT NULL DEFAULT 0,
    added_at     TEXT NOT NULL,
    UNIQUE (course_id, content_hash)
);

CREATE TABLE IF NOT EXISTS chunks (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    material_id     INTEGER NOT NULL REFERENCES materials(id) ON DELETE CASCADE,
    course_id       INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    position        INTEGER NOT NULL,
    text            TEXT NOT NULL,
    page_number     INTEGER NOT NULL,
    embedding       BLOB,
    embedding_model TEXT
);
CREATE INDEX IF NOT EXISTS idx_chunks_course ON chunks(course_id);
CREATE INDEX IF NOT EXISTS idx_chunks_material ON chunks(material_id);

CREATE TABLE IF NOT EXISTS concepts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    course_id    INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    definition   TEXT NOT NULL DEFAULT '',
    importance   INTEGER NOT NULL DEFAULT 3,
    source_pages TEXT NOT NULL DEFAULT '[]'
);
CREATE INDEX IF NOT EXISTS idx_concepts_course ON concepts(course_id);

CREATE TABLE IF NOT EXISTS concept_prerequisites (
    concept_id      INTEGER NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
    prerequisite_id INTEGER NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
    PRIMARY KEY (concept_id, prerequisite_id)
);

CREATE TABLE IF NOT EXISTS concept_materials (
    concept_id  INTEGER NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
    material_id INTEGER NOT NULL REFERENCES materials(id) ON DELETE CASCADE,
    PRIMARY KEY (concept_id, material_id)
);

CREATE TABLE IF NOT EXISTS summaries (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    material_id INTEGER NOT NULL REFERENCES materials(id) ON DELETE CASCADE,
    length      TEXT NOT NULL,
    from_page   INTEGER,
    to_page     INTEGER,
    overview    TEXT NOT NULL,
    key_points  TEXT NOT NULL,
    key_terms   TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS qa_history (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    course_id   INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    question    TEXT NOT NULL,
    answer_json TEXT NOT NULL,
    asked_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS quizzes (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    course_id  INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    difficulty TEXT
);

CREATE TABLE IF NOT EXISTS questions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    quiz_id         INTEGER NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE,
    position        INTEGER NOT NULL,
    type            TEXT NOT NULL,
    stem            TEXT NOT NULL,
    difficulty      TEXT NOT NULL,
    source_page     INTEGER NOT NULL,
    concept_id      INTEGER REFERENCES concepts(id) ON DELETE SET NULL,
    source_chunk_id INTEGER REFERENCES chunks(id) ON DELETE SET NULL,
    answer_key      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_questions_quiz ON questions(quiz_id);

CREATE TABLE IF NOT EXISTS quiz_attempts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    quiz_id      INTEGER NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE,
    course_id    INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    status       TEXT NOT NULL,
    submitted_at TEXT
);

-- One row per question of the attempt; grade columns stay NULL until the answer is graded.
CREATE TABLE IF NOT EXISTS attempt_answers (
    attempt_id   INTEGER NOT NULL REFERENCES quiz_attempts(id) ON DELETE CASCADE,
    question_id  INTEGER NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    answer       TEXT NOT NULL DEFAULT '',
    score        REAL,
    correct      INTEGER,
    feedback     TEXT,
    graded_by_ai INTEGER,
    pending      INTEGER,
    overridden   INTEGER,
    PRIMARY KEY (attempt_id, question_id)
);

CREATE TABLE IF NOT EXISTS explanations (
    attempt_id       INTEGER NOT NULL REFERENCES quiz_attempts(id) ON DELETE CASCADE,
    question_id      INTEGER NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    text             TEXT NOT NULL,
    misconception    TEXT,
    citation_json    TEXT,
    source_available INTEGER NOT NULL,
    PRIMARY KEY (attempt_id, question_id)
);

CREATE TABLE IF NOT EXISTS flashcards (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    course_id    INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    deck_id      INTEGER NOT NULL,
    concept_id   INTEGER REFERENCES concepts(id) ON DELETE SET NULL,
    front        TEXT NOT NULL,
    back         TEXT NOT NULL,
    source_page  INTEGER NOT NULL DEFAULT 0,
    box          INTEGER NOT NULL DEFAULT 1,
    next_due     TEXT NOT NULL,
    review_count INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_flashcards_course ON flashcards(course_id);

-- Flashcard ratings are mastery evidence (F09) and history entries (F13).
CREATE TABLE IF NOT EXISTS flashcard_reviews (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    card_id     INTEGER NOT NULL REFERENCES flashcards(id) ON DELETE CASCADE,
    course_id   INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    concept_id  INTEGER REFERENCES concepts(id) ON DELETE SET NULL,
    rating      TEXT NOT NULL,
    reviewed_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS study_plans (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    course_id        INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    exam_date        TEXT NOT NULL,
    created_at       TEXT NOT NULL,
    omitted_concepts TEXT NOT NULL DEFAULT '[]'
);

CREATE TABLE IF NOT EXISTS study_sessions (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id    INTEGER NOT NULL REFERENCES study_plans(id) ON DELETE CASCADE,
    date       TEXT NOT NULL,
    minutes    INTEGER NOT NULL,
    concepts   TEXT NOT NULL DEFAULT '[]',
    activities TEXT NOT NULL DEFAULT '',
    done       INTEGER NOT NULL DEFAULT 0
);

-- Study history (F13): one row per notable action.
CREATE TABLE IF NOT EXISTS activity_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    course_id   INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    type        TEXT NOT NULL,
    description TEXT NOT NULL,
    occurred_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_activity_course ON activity_log(course_id, occurred_at);

-- Study Coach preference profile (F12), kept across sessions.
CREATE TABLE IF NOT EXISTS agent_preferences (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    preference TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL
);
