package studybuddy.presentation.cli;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import studybuddy.presentation.gui.MainWindow;

/** {@code studybuddy gui}: opens the graphical interface and waits until its window is closed. */
@Command(name = "gui", description = "Open the graphical interface.")
public class GuiCommand implements Callable<Integer> {

    @ParentCommand
    StudyAgentCli root;

    @Override
    public Integer call() throws InterruptedException {
        CountDownLatch closed = new CountDownLatch(1);
        MainWindow.launch(root.facade(), closed::countDown);
        closed.await();
        return 0;
    }
}
