package studybuddy.presentation.gui;

import java.awt.Color;
import java.awt.Font;

import javax.swing.UIManager;

/** The GUI's look in one place: change a value here and every panel follows. */
final class Theme {

    /** Font of all multi-line text (Ask question and answer, concept details, ...). */
    static final String TEXT_FONT_NAME = "Roboto";
    static final int TEXT_FONT_SIZE = 14;

    /** Height of every table row; used by {@link StudyTable}. */
    static final int TABLE_ROW_HEIGHT = 20;

    static final Color AI_CONNECTED = new Color(46, 139, 87);
    static final Color AI_NOT_CONNECTED = new Color(176, 128, 0);

    /** Colours of a graded answer in the quiz results. */
    static final Color ANSWER_CORRECT = new Color(46, 139, 87);
    static final Color ANSWER_WRONG = new Color(192, 57, 43);
    static final Color ANSWER_PENDING = new Color(176, 128, 0);

    private Theme() {
    }

    /**
     * Sets the Swing defaults that new components read when they are created, so this must run before
     * the panels are built. If the font is not installed, Java falls back to its proportional Dialog font.
     */
    static void install() {
        UIManager.put("TextArea.font", textFont());
    }

    /** The reading font, for other components that show course text (quiz options, for example). */
    static Font textFont() {
        return new Font(TEXT_FONT_NAME, Font.PLAIN, TEXT_FONT_SIZE);
    }
}
