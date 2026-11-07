# StudyBuddy: AI Study and Learning Agent

StudyBuddy helps a student study from their own course materials. It reads PDFs and notes, answers questions with citations to the pages it used, extracts key concepts, writes flashcards and quizzes, grades quiz attempts, explains answers, tracks which concepts are weak, and records study progress. A Study Coach agent plans and carries out multi-step study goals using these features as tools.

It runs on your computer as a Java desktop application (Swing GUI) and a command-line interface (`studybuddy`). Data is stored locally in SQLite. The AI features use Google Gemini through LangChain4j.

## Features

| ID | Feature | AI |
|----|---------|----|
| F01 | Course and material management (upload PDF, TXT or Markdown) | No; embeddings when a key is set |
| F02 | Concept extraction | Yes |
| F03 | Question answering with citations | Hybrid |
| F04 | Flashcard generation and spaced-repetition review | Hybrid |
| F05 | Quiz generation | Yes |
| F06 | Quiz grading | Hybrid |
| F07 | Answer explanations | Yes |
| F08 | Weak concepts | Hybrid |
| F09 | Study Coach agent | Yes |
| F10 | Progress and study history | No |

## Prerequisites

- **Java 25 (JDK)**. Check with `java -version`. Building needs the JDK; running the jar needs Java 25 or newer.
- **Git**, to clone the repository.
- **A Gemini API key** (optional, free from [Google AI Studio](https://aistudio.google.com/apikey)) and an internet connection for the AI features. Without a key the app still runs; see [Running without AI](#running-without-ai).

Maven does not need to be installed: the repository includes the Maven Wrapper (`mvnw`, `mvnw.cmd`), which downloads Maven and all dependencies on the first build.

## Setup

### 1. Clone

```bash
git clone https://github.com/manseband/studybuddy.git
cd studybuddy
```

### 2. Add your API key

Copy the example configuration and put your key in the copy:

```bash
cp .env.example .env
```

On Windows Command Prompt: `copy .env.example .env`

Then open `.env` and replace the placeholder:

```
GEMINI_API_KEY=your-key-here
```

`.env` is listed in `.gitignore`, so the key stays on your computer. Never commit it. A real `GEMINI_API_KEY` environment variable also works and takes priority over `.env`.

Other settings (models, database path, chunk size and so on) have defaults in `src/main/resources/studybuddy.properties`. Any of them can be overridden in `.env` or the environment as `STUDYBUDDY_` + the key in upper case with dots as underscores, for example `STUDYBUDDY_DB_PATH=data/other.db`.

### 3. Build

```bash
./mvnw verify
```

On Windows: `mvnw.cmd verify`

This compiles the code, runs the JUnit tests and builds `target/studybuddy.jar`, which contains everything needed to run. The tests never call Gemini; they use a scripted fake model. For a faster build without tests, use `./mvnw -DskipTests package`. Rebuild after pulling new changes.

## Running

Run these commands from the project folder: `.env` and the `data/` folder are looked up in the current folder.

| | Windows | macOS, Linux, Git Bash |
|---|---|---|
| GUI | `.\studybuddy` (or double-click `studybuddy.cmd`) | `./studybuddy` |
| CLI | `.\studybuddy course list` | `./studybuddy course list` |

`.\studybuddy` works in both Command Prompt and PowerShell. The launchers build the jar first if it does not exist yet. Without them, run the jar directly:

```bash
java -jar target/studybuddy.jar gui
java -jar target/studybuddy.jar course list
```

The rest of this README writes commands as `studybuddy ...`; type `.\studybuddy ...` on Windows and `./studybuddy ...` on macOS and Linux.

### The GUI

The window has a course selector at the top and one tab per area: **Materials** (F01), **Concepts** (F02), **Ask** (F03), **Flashcards** (F04), **Quizzes** (F05, F06, F07), **Progress** (F08, F10) and **Coach** (F09). The GUI and CLI use the same services and the same database, so anything created in one appears in the other.

### Your data

Everything is stored in `data/studybuddy.db`, created on the first run. There is no reset command: to start over, close the app and delete that file. Deleting a course from the GUI or CLI removes its materials, concepts, quizzes, flashcards and history.

### Running without AI

Without a key, the GUI shows "AI: no API key" and these still work: courses, uploading materials, keyword search, reviewing existing flashcards, taking existing quizzes (short answers stay pending until they can be graded), and progress and history. Features that need the model stop with a message explaining how to add a key; Ask still lists the matching excerpts.

To try this while a key is in `.env`, set the placeholder for one terminal session (it overrides `.env`):

```bash
export GEMINI_API_KEY=YOUR_LOCAL_API_KEY_HERE
```

On Windows Command Prompt: `set GEMINI_API_KEY=YOUR_LOCAL_API_KEY_HERE`. Close the terminal to go back to using the key.

## CLI reference

`studybuddy --help` lists the commands. Running a command without its required options prints its usage.

- Every command accepts `--json` to print machine-readable JSON instead of text.
- A course can be given by name or by id; materials, concepts, decks, quizzes and attempts by the id shown in listings.
- Exit codes: `0` success, `1` error (the message starts with `Error:`), `2` wrong options or an unconfirmed `course delete`.

### Courses and materials (F01)

| Command | What it does |
|---|---|
| `course create <name> [--code <code>]` | Creates a course. Names are unique, ignoring case. |
| `course list` | Lists courses with their ids. |
| `course delete <course> [--yes]` | Without `--yes`, says what would be deleted. With `--yes`, deletes the course and everything in it. |
| `material add --course <c> <file> [--replace]` | Uploads a PDF, TXT or MD file of up to 20 MB. The text is extracted page by page (Apache PDFBox for PDFs), split into chunks of about 400 words that overlap by 50 words and keep their page numbers, and indexed for search: with embeddings (`gemini-embedding-001`) when a key is set, otherwise with keyword ranking. A file with the same content as one already in the course is refused unless `--replace` is given. Scanned PDFs without a text layer give no text (no OCR). |
| `material list --course <c>` | Lists materials with pages, words, chunks and status. |
| `material rename <id> <title>` | Renames a material. |
| `material remove <id>` | Removes a material and its search index entries. |
| `material reindex --course <c>` | Retries embeddings for chunks that have none, for example after a rate limit. The GUI does this automatically when a course is opened. |

### Concepts (F02)

| Command | What it does |
|---|---|
| `concepts extract --material <id> [--preview]` | The model reads the material in parts and returns each key concept with a one-sentence definition, source pages, importance (1-5) and prerequisites. Concepts with the same name as an existing one are merged into it. They are saved straight away; `--preview` only shows them. (The GUI shows them for review first.) |
| `concepts list --course <c>` | Lists the course's concepts. |
| `concepts show <id>` | Shows a concept with its prerequisites and the materials it appears in. |
| `concepts edit <id> [--name] [--definition] [--importance]` | Changes a concept. |
| `concepts merge <fromId> <intoId>` | Merges the first concept into the second: question and flashcard tags, pages and links move to the second. |
| `concepts delete <id>` | Deletes a concept. Questions and flashcards tagged with it become untagged. |

Concepts are the topics the rest of the app tracks: questions and flashcards are tagged with them, and mastery (F08) is calculated per concept.

### Asking questions (F03)

| Command | What it does |
|---|---|
| `ask --course <c> [--general] <question>` | Finds the 6 most relevant excerpts and has the model answer from them only, with numbered citations to material and page. If the materials do not cover the question it says so; with `--general` it then answers from general knowledge, clearly labelled. Questions are kept in the study history. |

### Flashcards (F04)

| Command | What it does |
|---|---|
| `flashcards generate --course <c> [--count 5-20] [--material <ids>] [--concepts <names or ids>] [--preview]` | The model writes a deck from the chosen materials or concepts, or the whole course. Cards are checked (no empty, too long or repeated cards, including repeats of existing cards) and tagged with a concept where one fits. If the material is short, fewer cards are written and the output says so. Saved as a new deck unless `--preview`. |
| `flashcards list --course <c>` | Lists decks and how many cards are due today. |
| `flashcards show <deckId>` | Shows a deck's cards with their concept and next due date. |
| `flashcards review --deck <id>` or `--course <c>` | Shows each due card, then asks you to rate your recall: again, hard, good or easy. Cards are scheduled in Leitner boxes 1-5, reviewed every 1, 2, 4, 8 or 16 days: again sends a card back to box 1, hard keeps it, good moves it up one box and easy two. Ratings on tagged cards count towards that concept's mastery. Works without an API key. |
| `flashcards delete <deckId>` | Deletes a deck. Ratings already given are kept, so mastery and history do not change. |

### Quizzes, grading and explanations (F05, F06, F07)

| Command | What it does |
|---|---|
| `quiz generate --course <c> [--count 1-30] [--types mcq,tf,short] [--difficulty easy\|medium\|hard] [--material <ids>] [--concept <ids>]` | The model writes multiple-choice, true/false and short-answer questions from the course excerpts, each with its source page and concept. Invalid or repeated questions are dropped. |
| `quiz list --course <c>` | Lists quizzes with their latest score. |
| `quiz show <id>` | Shows a quiz's questions without the answers. |
| `quiz take <id>` | Asks each question in the terminal (A-D, t/f, or one line of text; Enter skips), then grades the attempt. |
| `quiz grade --attempt <file>` | Grades prepared answers from a JSON file such as `{"quizId": 7, "answers": {"12": "A", "13": "true"}}`. |
| `quiz results <attemptId>` | Shows the score and the feedback for each question. |
| `quiz regrade <attemptId>` | Grades answers that are still pending. |
| `quiz dispute <attemptId> --question <id> --score <0-1>` | Replaces an AI-assigned grade with your own score. |
| `explain --attempt <id> --question <id>` | Explains why the answer is right or wrong, citing the source excerpt, and names the likely misconception for a wrong answer. Explanations are saved, so asking again costs nothing. |

Grading uses two strategies: multiple-choice and true/false answers are compared with the answer key, and short answers are graded by the model against a rubric and reference answer, with partial credit. If the model is unavailable, short answers are marked pending instead of failing the attempt.

### Weak concepts (F08)

| Command | What it does |
|---|---|
| `weak-concepts --course <c> [--diagnose]` | Ranks the course's concepts by mastery (0-100), calculated from quiz answers and flashcard ratings, with newer evidence counting more. A concept needs at least 3 pieces of evidence; below 60 it is weak, otherwise strong. `--diagnose` also asks the model for patterns across your mistakes on weak concepts and suggests actions. |

### Study Coach (F09)

| Command | What it does |
|---|---|
| `coach [--course <c>]` | Starts a conversation in the terminal; type `exit` to finish. |
| `coach [--course <c>] --once "<goal>"` | Sends one message and exits. |

The coach takes a goal such as "quiz me on what I keep getting wrong", plans the steps and carries them out with its tools: search materials, get weak concepts, generate a quiz, generate a flashcard deck and save a preference. It shows each step as it runs, uses at most 8 tool calls per message, remembers the conversation, and can only read and create data, never change or delete it. Preferences you state ("I prefer short quizzes") are kept across conversations.

### Progress and history (F10)

| Command | What it does |
|---|---|
| `progress --course <c>` | Shows the average quiz score, the current study streak (days in a row with a quiz taken, cards reviewed, a question asked or a coach conversation) and the mastery of each concept. |
| `progress export --course <c> --format markdown\|csv --out <file>` | Saves the full report (summary, mastery and history) to a file, replacing it if it exists. |
| `history --course <c> [--since YYYY-MM-DD] [--until YYYY-MM-DD] [--type material\|question\|quiz\|flashcards\|coach]` | Lists study activity, newest first: materials uploaded, questions asked, quizzes created and taken, decks created, cards reviewed per day and coach conversations. |

## Sample data

The `samples/` folder has three short software-design PDFs (3 to 4 pages each), written for this project and free to share, so every feature can be tried without your own files:

| File | Topic |
|---|---|
| `samples/solid-principles.pdf` | The SOLID design principles |
| `samples/design-patterns.pdf` | Common object-oriented design patterns |
| `samples/software-testing.pdf` | Software testing |

## Example session

This walks through every feature in the CLI with the sample PDFs, starting from an empty database. In a new database the three PDFs become materials 1, 2 and 3, and the first quiz and attempt get id 1. Ids are always printed by the command that creates something and by the `list` commands.

```bash
# F01: a course with the three sample materials
studybuddy course create EECS3311 --code "Software Design"
studybuddy material add --course EECS3311 samples/solid-principles.pdf
studybuddy material add --course EECS3311 samples/design-patterns.pdf
studybuddy material add --course EECS3311 samples/software-testing.pdf
studybuddy material list --course EECS3311

# F02: the key concepts of each material, merged into one list for the course
studybuddy concepts extract --material 1
studybuddy concepts extract --material 2
studybuddy concepts extract --material 3
studybuddy concepts list --course EECS3311

# F03: questions answered from all three materials, with citations
studybuddy ask --course EECS3311 "How do design patterns help a class follow the open-closed principle?"
studybuddy ask --course EECS3311 "Why is code that depends on abstractions easier to test?"

# F04: a deck from one material, then a review of every due card
studybuddy flashcards generate --course EECS3311 --material 2 --count 10
studybuddy flashcards review --course EECS3311

# F05, F06, F07: a quiz from two materials, taken, graded and explained
studybuddy quiz generate --course EECS3311 --material 1,3 --count 5 --difficulty easy
studybuddy quiz take 1
studybuddy quiz results 1
studybuddy explain --attempt 1 --question 2

# F08: weak concepts, with an AI diagnosis of the mistakes
studybuddy weak-concepts --course EECS3311 --diagnose

# F09: the Study Coach plans and runs a multi-step goal
studybuddy coach --course EECS3311 --once "Find my weakest concept and make me flashcards and a short quiz on it"

# F10: progress, history and an exported report
studybuddy progress --course EECS3311
studybuddy history --course EECS3311 --type quiz
studybuddy progress export --course EECS3311 --format markdown --out progress.md
```

`explain` needs a graded answer: question 2 belongs to quiz 1 in a new database, and `quiz results 1` lists the question ids. Concepts need some evidence before they count as weak, so `weak-concepts` shows "Not enough data" until a few quiz answers or flashcard ratings exist for a concept.

## Project structure

| Path | Contents |
|---|---|
| `src/main/java/studybuddy/presentation` | Swing GUI (`gui`) and picocli CLI (`cli`); both talk only to `StudyAgentFacade` |
| `src/main/java/studybuddy/application` | The facade, services for each feature, event bus |
| `src/main/java/studybuddy/agent` | Study Coach: controller, planner, memory, tools |
| `src/main/java/studybuddy/ai` | Gemini adapter, fake model for tests, prompts, response parsing, document parsing, retrieval; the only package that uses LangChain4j |
| `src/main/java/studybuddy/grading` | Grading strategies |
| `src/main/java/studybuddy/domain` | Domain model |
| `src/main/java/studybuddy/persistence` | SQLite schema and repositories |
| `src/test/java` | JUnit 5 tests |
| `samples/` | Sample PDFs for trying the app |
| `studybuddy`, `studybuddy.cmd` | Launchers for macOS/Linux and Windows |
| `report/` | Stage reports |
| `uml/` | UMLet diagrams (`.uxf`, open with [UMLet](https://www.umlet.com/)) |

## Troubleshooting

- **"This model is currently experiencing high demand" (503):** Gemini is temporarily overloaded. StudyBuddy retries automatically; if it still fails, try again a few minutes later.
- **"This feature needs a Gemini API key":** `.env` is missing, still has the placeholder, or the command was not run from the project folder.
- **Some chunks have no embeddings after a rate limit:** run `studybuddy material reindex --course <c>`, or reopen the course in the GUI.
- **`UnsupportedClassVersionError`:** the `java` on your PATH is older than 25.
