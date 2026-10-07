package hits.ui;

import hits.Beat;
import hits.BeatFiles;
import hits.Editor;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Open, save, save as, new, kit replace, the unsaved-changes prompt, and the beats folder. */
final class FileCommands {
    private final JFrame frame;
    private final Editor editor;
    private final SaveSession session;
    private final BeatsFolder beatsFolder;
    private final StatusLine status;
    private final BeatBrowser browser;
    private final Runnable commitDocumentName;
    private final Runnable commitTrackName;
    private final Runnable showDocumentName;
    private final Runnable updateTitle;

    FileCommands(
        JFrame frame,
        Editor editor,
        SaveSession session,
        BeatsFolder beatsFolder,
        StatusLine status,
        BeatBrowser browser,
        Runnable commitDocumentName,
        Runnable commitTrackName,
        Runnable showDocumentName,
        Runnable updateTitle
    ) {
        this.frame = frame;
        this.editor = editor;
        this.session = session;
        this.beatsFolder = beatsFolder;
        this.status = status;
        this.browser = browser;
        this.commitDocumentName = commitDocumentName;
        this.commitTrackName = commitTrackName;
        this.showDocumentName = showDocumentName;
        this.updateTitle = updateTitle;
    }

    void loadSelected() {
        BeatFiles.Entry entry = browser.selected();
        if (entry == null || !confirmProceed()) {
            return;
        }
        try {
            editor.replace(BeatFiles.load(entry.path()), false);
            session.loaded(entry.path());
            status.set("Loaded " + entry.label());
        } catch (RuntimeException | IOException exception) {
            JOptionPane.showMessageDialog(frame, exception.getMessage(), "Hits", JOptionPane.ERROR_MESSAGE);
        }
    }

    boolean save() {
        commitDocumentName.run();
        commitTrackName.run();
        return writeFile(false);
    }

    boolean saveAs() {
        commitTrackName.run();
        String initial = editor.beat().name();
        JTextField nameField = new JTextField(initial, 28);
        JLabel fileLabel = new JLabel(" ");
        Runnable refreshLabel = () -> fileLabel.setText(SaveSession.saveAsCaption(nameField.getText(), beatsFolder.get()));
        onText(nameField, refreshLabel);
        refreshLabel.run();
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(new JLabel("Name stored in the file"));
        form.add(nameField);
        form.add(Box.createVerticalStrut(8));
        form.add(fileLabel);
        int choice = JOptionPane.showConfirmDialog(frame, form, "Save As", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return false;
        }
        String typed = nameField.getText().trim();
        if (BeatFiles.safeName(typed).isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Add a letter or number to the name before saving.", "Hits", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (!typed.equals(editor.beat().name())) {
            editor.edit(beat -> beat.setName(typed));
        }
        showDocumentName.run();
        return writeFile(true);
    }

    void fresh() {
        if (!confirmProceed()) {
            return;
        }
        session.forget();
        editor.replace(Beat.drumKit("untitled"), false);
        status.set("New kit");
    }

    void applyKit(String name, Consumer<Beat> kit) {
        String slot = ChainLabels.slotName(editor.viewSlot());
        int choice = JOptionPane.showConfirmDialog(
            frame,
            name + " replaces pattern " + slot + ", the tempo, and the swing.\nThe other pattern is kept.",
            "Hits",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (choice == JOptionPane.OK_OPTION) {
            char view = editor.viewSlot();
            editor.edit(beat -> {
                beat.setActiveSlot(view);
                kit.accept(beat);
            });
            status.set(name + " on pattern " + slot);
        }
    }

    void chooseBeatsFolder() {
        JFileChooser chooser = new JFileChooser(beatsFolder.get().toFile());
        chooser.setDialogTitle("Beats folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        beatsFolder.choose(chooser.getSelectedFile().toPath());
        browser.noteFolder();
        browser.reload(session.file());
        status.set("Beats folder: " + beatsFolder.get());
    }

    /** Save, discard, or cancel before replacing or closing the open beat. */
    boolean confirmProceed() {
        if (!editor.isDirty()) {
            return true;
        }
        String[] options = {"Save", "Don't Save", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
            frame,
            "Save changes to \"" + editor.beat().name() + "\"?",
            "Hits",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]
        );
        return SaveSession.proceed(true, SaveSession.choice(choice), this::save);
    }

    private boolean writeFile(boolean alreadyConfirmed) {
        String display = editor.beat().name();
        String safe = BeatFiles.safeName(display);
        if (safe.isEmpty()) {
            JOptionPane.showMessageDialog(
                frame,
                "Add a letter or number to the name before saving.",
                "Hits",
                JOptionPane.WARNING_MESSAGE
            );
            return false;
        }
        Path target = beatsFolder.get().resolve(safe + ".json");
        boolean exists = Files.exists(target);
        BeatFiles.SaveChoice choice = BeatFiles.plan(display, target, session.file(), false, exists);
        if (!alreadyConfirmed && choice != BeatFiles.SaveChoice.WRITE) {
            String message = SaveSession.confirmMessage(choice, safe, display);
            String[] options = SaveSession.confirmOptions(choice);
            int answer = JOptionPane.showOptionDialog(
                frame,
                message,
                "Save",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
            );
            if (answer != 0) {
                return false;
            }
        }
        try {
            Path path = BeatFiles.write(editor.beat(), target);
            editor.markClean();
            session.rememberWritten(path);
            browser.reload(path);
            status.set("Saved " + path.getFileName());
            updateTitle.run();
            return true;
        } catch (RuntimeException | IOException exception) {
            JOptionPane.showMessageDialog(frame, Widgets.message(exception), "Hits", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private static void onText(JTextField field, Runnable listener) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                listener.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                listener.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                listener.run();
            }
        });
    }
}
