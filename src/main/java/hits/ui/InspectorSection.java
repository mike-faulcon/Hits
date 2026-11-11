package hits.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumMap;
import java.util.function.Consumer;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleAction;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;

/**
 * One collapsible group in the row inspector. The header is a button: Enter and
 * Space toggle it, and the accessible name includes the active summary. The summary
 * stays on the header when the body is hidden.
 */
final class InspectorSection extends JPanel {
    /** Client property on the header. Space plays unless the focused component has this. */
    static final String HEADER = "hits.inspectorSection";

    private final SectionSummary.Id id;
    private final JPanel header;
    private JLabel titleLabel;
    private JLabel badge;
    private JLabel summaryLabel;
    private final JPanel body;
    private final String tip;
    private Consumer<Boolean> onChoice = open -> {};
    private boolean open;
    private boolean updating;
    private String summary = "";

    InspectorSection(SectionSummary.Id id) {
        super(new GridBagLayout());
        this.id = id;
        setOpaque(false);
        setName("section-" + slug());
        tip = "Show or hide " + id.title() + ". Enter or Space toggles this section.";
        header = buildHeader();
        body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        body.setName("section-" + slug() + "-body");
        body.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 0));
        body.setVisible(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(0, 0, 6, 0);
        add(header, constraints);
        constraints.gridy = 1;
        constraints.insets = new Insets(0, 0, 2, 0);
        add(body, constraints);
        paintHeader();
    }

    private JPanel buildHeader() {
        JPanel bar = new JPanel(new BorderLayout(0, 2)) {
            @Override
            public AccessibleContext getAccessibleContext() {
                if (accessibleContext == null) {
                    accessibleContext = new AccessibleJPanel() {
                        @Override
                        public AccessibleRole getAccessibleRole() {
                            return AccessibleRole.PUSH_BUTTON;
                        }

                        @Override
                        public int getAccessibleChildrenCount() {
                            return 0;
                        }

                        @Override
                        public Accessible getAccessibleChild(int index) {
                            return null;
                        }

                        @Override
                        public AccessibleAction getAccessibleAction() {
                            return new AccessibleAction() {
                                @Override
                                public int getAccessibleActionCount() {
                                    return 1;
                                }

                                @Override
                                public String getAccessibleActionDescription(int index) {
                                    return open ? "Collapse" : "Expand";
                                }

                                @Override
                                public boolean doAccessibleAction(int index) {
                                    toggle();
                                    return true;
                                }
                            };
                        }
                    };
                }
                return accessibleContext;
            }
        };
        bar.setName("section-" + slug() + "-header");
        bar.setOpaque(true);
        bar.setBackground(Theme.BG);
        bar.setFocusable(true);
        bar.setRequestFocusEnabled(true);
        bar.putClientProperty(HEADER, Boolean.TRUE);
        bar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        bar.setToolTipText(tip);
        bar.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggle-section");
        bar.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle-section");
        bar.getActionMap().put("toggle-section", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                toggle();
            }
        });
        bar.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                paintChrome(bar);
            }

            @Override
            public void focusLost(FocusEvent event) {
                paintChrome(bar);
            }
        });
        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                toggle();
                bar.requestFocusInWindow();
            }
        };
        bar.addMouseListener(click);

        titleLabel = new JLabel(id.title());
        titleLabel.setForeground(Theme.TEXT);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setToolTipText(tip);
        if (id == SectionSummary.Id.SOUND) {
            titleLabel.setName("sound-heading");
        }
        badge = new JLabel("active");
        badge.setName("section-" + slug() + "-badge");
        badge.setOpaque(true);
        badge.setBackground(Theme.STEP_ACCENT);
        badge.setForeground(Theme.INK);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 11f));
        badge.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
        badge.setToolTipText(tip);
        summaryLabel = new JLabel(" ");
        summaryLabel.setName("section-" + slug() + "-summary");
        summaryLabel.setForeground(Theme.STEP_ACCENT);
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.PLAIN, 12f));
        summaryLabel.setToolTipText(tip);
        summaryLabel.setVisible(false);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(titleLabel, BorderLayout.CENTER);
        top.add(badge, BorderLayout.EAST);
        bar.add(top, BorderLayout.NORTH);
        bar.add(summaryLabel, BorderLayout.SOUTH);
        for (JComponent child : new JComponent[] {top, titleLabel, badge, summaryLabel}) {
            child.addMouseListener(click);
            child.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        paintChrome(bar);
        return bar;
    }

    private void paintChrome(JPanel bar) {
        Color line = bar.isFocusOwner() ? Theme.FOCUS : Theme.STEP_OFF;
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(line, 2),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        ));
    }

    JPanel body() {
        return body;
    }

    JComponent header() {
        return header;
    }

    boolean isOpen() {
        return open;
    }

    String summaryText() {
        return summary;
    }

    void onChoice(Consumer<Boolean> choice) {
        onChoice = choice == null ? open -> {} : choice;
    }

    /** Shows or hides the section. Does not record a user choice. */
    void show(boolean applies, boolean expanded, String summaryText) {
        updating = true;
        setVisible(applies);
        open = expanded;
        summary = summaryText == null ? "" : summaryText;
        body.setVisible(applies && expanded);
        paintHeader();
        updating = false;
    }

    private void toggle() {
        if (updating) {
            return;
        }
        open = !open;
        onChoice.accept(open);
        body.setVisible(isVisible() && open);
        paintHeader();
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
    }

    private void paintHeader() {
        titleLabel.setText((open ? "▾ " : "▸ ") + id.title());
        boolean active = !summary.isEmpty();
        badge.setVisible(active);
        summaryLabel.setVisible(active);
        if (active) {
            summaryLabel.setText("<html><body style='width: 240px; color: #FFD27A'>"
                + escape(summary) + "</body></html>");
        } else {
            summaryLabel.setText(" ");
        }
        summaryLabel.putClientProperty("summary", summary);
        header.getAccessibleContext().setAccessibleName(SectionSummary.accessibleName(id, summary, open));
        header.getAccessibleContext().setAccessibleDescription(tip);
        header.revalidate();
        header.repaint();
    }

    private String slug() {
        return id.name().toLowerCase();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }
}

/** Open, closed, or still following whatever the selected row is doing. */
final class SectionMemory {
    private enum Choice { UNSET, OPEN, CLOSED }

    private final EnumMap<SectionSummary.Id, Choice> choices = new EnumMap<>(SectionSummary.Id.class);

    SectionMemory() {
        for (SectionSummary.Id id : SectionSummary.Id.values()) {
            choices.put(id, Choice.UNSET);
        }
        choices.put(SectionSummary.Id.SOUND, Choice.OPEN);
    }

    /**
     * Sound starts open. Any other section starts by following the row: open while
     * that row's settings differ from the defaults, closed when they do not.
     * Opening or closing a header remembers that choice for the rest of the session.
     */
    boolean expanded(SectionSummary.Id id, boolean active) {
        return switch (choices.get(id)) {
            case OPEN -> true;
            case CLOSED -> false;
            case UNSET -> active;
        };
    }

    void choose(SectionSummary.Id id, boolean expanded) {
        choices.put(id, expanded ? Choice.OPEN : Choice.CLOSED);
    }
}
