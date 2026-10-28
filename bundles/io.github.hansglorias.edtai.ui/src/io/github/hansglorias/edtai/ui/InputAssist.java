package io.github.hansglorias.edtai.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.fieldassist.ContentProposal;
import org.eclipse.jface.fieldassist.ContentProposalAdapter;
import org.eclipse.jface.fieldassist.IContentProposal;
import org.eclipse.jface.fieldassist.TextContentAdapter;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Text;

import io.github.hansglorias.edtai.core.chat.InputCompletion;
import io.github.hansglorias.edtai.core.chat.SlashCommand;
import io.github.hansglorias.edtai.core.context.ModuleSearch;
import io.github.hansglorias.edtai.core.skills.Skill;

/**
 * Подсказки в поле ввода: после {@code @} — модули проекта (выбранный
 * прикрепляется к вопросу целиком), {@code /} в начале — команды и скиллы.
 */
final class InputAssist {

    private static final int MAX_MODULES = 50;
    /** Сколько держать список модулей: обход большой конфигурации заметен на каждой букве. */
    private static final long MODULES_TTL_MS = 30_000;

    /** Модуль в списке подсказок. */
    private static final class ModuleProposal extends ContentProposal {
        final IFile file;

        ModuleProposal(String name, IFile file) {
            super("@" + name, name, file.getFullPath().makeRelative().toString());
            this.file = file;
        }
    }

    private final Text input;
    private final Consumer<IFile> attach;
    private final Supplier<List<Skill>> skills;
    private final ContentProposalAdapter adapter;
    private List<IFile> modules;
    private long modulesTime;

    /**
     * @param attach прикрепляет выбранный модуль к вопросу
     * @param skills скиллы: их команды — в подсказках после встроенных
     */
    InputAssist(Text input, Consumer<IFile> attach, Supplier<List<Skill>> skills) {
        this.input = input;
        this.attach = attach;
        this.skills = skills;
        adapter = new ContentProposalAdapter(input, new TextContentAdapter(), this::proposals, null, new char[] { '@', '/' });
        adapter.setAutoActivationDelay(100);
        adapter.setProposalAcceptanceStyle(ContentProposalAdapter.PROPOSAL_IGNORE);
        adapter.setPopupSize(new Point(420, 240));
        adapter.addContentProposalListener(this::accept);
    }

    /** Открыт список подсказок: Enter, Esc и стрелки — его. */
    boolean isOpen() {
        return adapter.isProposalPopupOpen();
    }

    IContentProposal[] proposals(String contents, int position) {
        InputCompletion completion = InputCompletion.at(contents, position).orElse(null);
        if (completion == null) {
            return new IContentProposal[0];
        }
        List<IContentProposal> proposals = new ArrayList<>();
        if (completion.kind() == InputCompletion.Kind.COMMAND) {
            for (SlashCommand command : SlashCommand.matching(completion.prefix())) {
                proposals.add(new ContentProposal(command.text(), command.text() + " — " + command.description(), null));
            }
            String prefix = completion.prefix().toLowerCase(Locale.ROOT);
            for (Skill skill : skills.get()) {
                if (skill.name().startsWith(prefix)) {
                    proposals.add(new ContentProposal(skill.command(), skill.command() + " — скилл: " + skill.description(),
                            skill.description()));
                }
            }
        } else {
            ModuleSearch search = new ModuleSearch(completion.prefix());
            modules().stream()
                    .map(file -> new ModuleProposal(ModuleSelectionDialog.name(file), file))
                    .filter(proposal -> search.matches(proposal.getLabel(), proposal.file.getFullPath().toString()))
                    .sorted(Comparator.comparing(IContentProposal::getLabel, String.CASE_INSENSITIVE_ORDER))
                    .limit(MAX_MODULES)
                    .forEach(proposals::add);
        }
        return proposals.toArray(IContentProposal[]::new);
    }

    private List<IFile> modules() {
        long now = System.currentTimeMillis();
        if (modules == null || now - modulesTime > MODULES_TTL_MS) {
            modules = ModuleSelectionDialog.modules();
            modulesTime = now;
        }
        return modules;
    }

    private void accept(IContentProposal proposal) {
        String text = input.getText();
        int caret = input.getCaretPosition();
        InputCompletion completion = InputCompletion.at(text, caret).orElse(null);
        if (completion == null) {
            return;
        }
        InputCompletion.Applied applied = completion.apply(text, caret, proposal.getContent());
        input.setText(applied.text());
        input.setSelection(applied.caret());
        if (proposal instanceof ModuleProposal module) {
            attach.accept(module.file);
        }
    }

    // ---- для тестов ----

    /** Выбор подсказки, как Enter в списке. */
    void acceptForTest(IContentProposal proposal) {
        accept(proposal);
    }
}
