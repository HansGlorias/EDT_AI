package io.github.hansglorias.edtai.core.tools;

import static io.github.hansglorias.edtai.core.tools.ProjectTools.PROJECT;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.integer;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.number;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.schema;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.string;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.text;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntSupplier;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import io.github.hansglorias.edtai.core.context.BslModulePath;

/**
 * Инструменты git: что изменено, дифф, история и откат файлов к последнему
 * коммиту ({@code git_restore}, с подтверждением). Через
 * JGit — он есть в EDT (EGit), отдельная программа {@code git} не нужна.
 * Работают, если каталог проекта лежит в git-репозитории; видят
 * сохранённые файлы, а не несохранённый текст редактора.
 */
public final class GitTools {

    static final int DEFAULT_LOG = 15;
    static final int MAX_LOG = 100;
    static final int MAX_STATUS = 300;

    private final WorkspaceFiles files;
    private final IntSupplier limit;

    private GitTools(WorkspaceFiles files, IntSupplier limit) {
        this.files = files;
        this.limit = limit;
    }

    /** Пустой список, если JGit нет: зависимость необязательная, чтобы плагин работал и без EGit. */
    public static List<Tool> create(WorkspaceFiles files, IntSupplier limit) {
        try {
            Class.forName("org.eclipse.jgit.api.Git", false, GitTools.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return List.of();
        }
        GitTools tools = new GitTools(files, limit);
        return List.of(tools.new StatusTool(), tools.new DiffTool(), tools.new LogTool(), tools.new RestoreTool());
    }

    // ---- инструменты ----

    private final class StatusTool implements Tool {
        @Override
        public String name() {
            return "git_status";
        }

        @Override
        public String description() {
            return "Какие файлы проекта изменены и не закоммичены (git): изменённые, новые, удалённые, конфликты."
                    + " Ветка и последний коммит. Вызывай, если спрашивают о текущих правках.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Map.of("project", PROJECT), List.of());
        }

        @Override
        public String summary(Map<String, Object> input) {
            return "Смотрит изменения в git";
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            try (Opened opened = open(input); Git git = new Git(opened.repository())) {
                StatusCommand command = git.status();
                if (!opened.prefix().isEmpty()) {
                    command.addPath(opened.prefix());
                }
                Status status = command.call();
                List<String> lines = new ArrayList<>();
                lines.add(head(opened.repository()));
                int count = 0;
                count += section(lines, "Изменены", opened, union(status.getModified(), status.getChanged()));
                count += section(lines, "Новые", opened, union(status.getUntracked(), status.getAdded()));
                count += section(lines, "Удалены", opened, union(status.getMissing(), status.getRemoved()));
                count += section(lines, "Конфликты", opened, status.getConflicting());
                if (count == 0) {
                    lines.add("Незакоммиченных изменений нет.");
                }
                return new Result(String.join("\n", lines), count == 0 ? "изменений нет" : "изменено файлов: " + count);
            } catch (GitAPIException | IOException e) {
                throw new ToolException("git status не выполнен: " + e.getMessage());
            }
        }
    }

    private final class DiffTool implements Tool {
        @Override
        public String name() {
            return "git_diff";
        }

        @Override
        public String description() {
            return "Дифф (unified) в git. Без commit — незакоммиченные изменения рабочей копии относительно последнего"
                    + " коммита (сохранённые файлы; новые неотслеживаемые файлы не входят — их список в git_status)."
                    + " С commit — что изменил этот коммит. module — только этот модуль (имя 1С или путь в проекте).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Map.of("module", string("Модуль: имя в нотации 1С или путь файла в проекте; пусто — весь проект"),
                    "commit", string("Хеш коммита (можно сокращённый); пусто — незакоммиченные изменения"), "project", PROJECT),
                    List.of());
        }

        @Override
        public String summary(Map<String, Object> input) {
            String module = text(input, "module");
            String commit = text(input, "commit");
            return "Смотрит дифф" + (commit.isEmpty() ? "" : " коммита " + commit) + (module.isEmpty() ? "" : " " + module);
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            try (Opened opened = open(input); ObjectReader reader = opened.repository().newObjectReader()) {
                Repository repository = opened.repository();
                String commit = text(input, "commit");
                AbstractTreeIterator oldTree;
                AbstractTreeIterator newTree;
                String title;
                if (commit.isEmpty()) {
                    ObjectId head = repository.resolve("HEAD^{tree}");
                    oldTree = head == null ? new EmptyTreeIterator() : tree(reader, head);
                    newTree = new FileTreeIterator(repository);
                    title = "Незакоммиченные изменения относительно " + head(repository);
                } else {
                    RevCommit revision = commit(repository, commit);
                    oldTree = revision.getParentCount() == 0 ? new EmptyTreeIterator()
                            : tree(reader, revision.getParent(0).getTree().getId());
                    newTree = tree(reader, revision.getTree().getId());
                    title = "Коммит " + describe(revision);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                List<DiffEntry> entries;
                try (DiffFormatter formatter = new DiffFormatter(out)) {
                    formatter.setRepository(repository);
                    try {
                        // Кириллица в путях — как есть, а не восьмеричными кодами.
                        formatter.setQuotePaths(false);
                    } catch (LinkageError e) {
                        // Старый JGit в EDT без этого метода: пути останутся в кодах, дифф всё равно верный.
                    }
                    formatter.setPathFilter(filter(opened, text(input, "module")));
                    entries = formatter.scan(oldTree, newTree);
                    formatter.format(entries);
                }
                if (entries.isEmpty()) {
                    return new Result(title + "\nРазличий нет.", "различий нет");
                }
                String diff = out.toString(StandardCharsets.UTF_8);
                int budget = limit.getAsInt();
                String text = title + "\nФайлов: " + entries.size() + "\n\n" + (diff.length() > budget
                        ? diff.substring(0, budget) + "\n(дифф обрезан по лимиту: " + budget + " из " + diff.length()
                                + " символов; укажите module, чтобы посмотреть один модуль)"
                        : diff);
                return new Result(text, "файлов: " + entries.size());
            } catch (IOException e) {
                throw new ToolException("git diff не выполнен: " + e.getMessage());
            }
        }
    }

    private final class LogTool implements Tool {
        @Override
        public String name() {
            return "git_log";
        }

        @Override
        public String description() {
            return "История коммитов git: хеш, дата, автор, сообщение. module — история одного модуля (кто и когда его"
                    + " менял). Что изменил коммит — git_diff с commit.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Map.of("module", string("Модуль: имя в нотации 1С или путь файла в проекте; пусто — весь проект"),
                    "max_count", integer("Сколько коммитов, по умолчанию " + DEFAULT_LOG), "project", PROJECT), List.of());
        }

        @Override
        public String summary(Map<String, Object> input) {
            String module = text(input, "module");
            return "Смотрит историю git" + (module.isEmpty() ? "" : " " + module);
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            try (Opened opened = open(input); Git git = new Git(opened.repository())) {
                if (opened.repository().resolve("HEAD") == null) {
                    return new Result("В репозитории ещё нет коммитов.", "коммитов нет");
                }
                int max = Math.max(1, Math.min(MAX_LOG, number(input, "max_count", DEFAULT_LOG)));
                LogCommand log = git.log().setMaxCount(max);
                String module = text(input, "module");
                String path = module.isEmpty() ? opened.prefix() : opened.repositoryPath(ProjectTools.modulePath(opened.project(), module));
                if (!path.isEmpty()) {
                    log.addPath(path);
                }
                List<String> lines = new ArrayList<>();
                for (RevCommit commit : log.call()) {
                    lines.add(describe(commit));
                }
                if (lines.isEmpty()) {
                    return new Result("Коммитов" + (module.isEmpty() ? "" : " с модулем " + module) + " нет.", "коммитов нет");
                }
                return new Result(String.join("\n", lines), "коммитов: " + lines.size());
            } catch (GitAPIException | IOException e) {
                throw new ToolException("git log не выполнен: " + e.getMessage());
            }
        }
    }

    /**
     * Откат незакоммиченных изменений файлов или каталогов к последнему
     * коммиту — как {@code git restore --source=HEAD}. Новые файлы не
     * удаляются. Необратимо, поэтому с подтверждением (в авторежиме — без).
     */
    private final class RestoreTool implements Tool {

        @Override
        public boolean modifies() {
            return true;
        }
        @Override
        public String name() {
            return "git_restore";
        }

        @Override
        public String description() {
            return "Отменяет незакоммиченные изменения: возвращает файлы или каталоги проекта к последнему коммиту (HEAD),"
                    + " удалённые — восстанавливает. Новые (неотслеживаемые) файлы не удаляет. Необратимо: правки пропадут."
                    + " Вызывай, только когда пользователь просит откатить. paths — пути в проекте (как в git_status) или"
                    + " имена модулей; каталог — весь объект, например src/Catalogs/Товары.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Map.of("paths", Map.of("type", "array", "items", Map.of("type", "string"), "description",
                    "Пути файлов или каталогов в проекте либо имена модулей"), "project", PROJECT), List.of("paths"));
        }

        @Override
        public boolean needsApproval() {
            return true;
        }

        @Override
        public String summary(Map<String, Object> input) {
            List<String> paths = paths(input);
            return "Откатывает в git: " + (paths.isEmpty() ? "?" : paths.get(0) + (paths.size() > 1 ? " и ещё " + (paths.size() - 1) : ""));
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            String project = ProjectTools.project(files, input);
            List<String> paths = new ArrayList<>();
            try (Opened opened = open(input); Git git = new Git(opened.repository())) {
                if (opened.repository().resolve("HEAD") == null) {
                    throw new ToolException("В репозитории ещё нет коммитов: откатывать не к чему");
                }
                Path root = files.root(opened.project()).orElseThrow();
                for (String raw : paths(input)) {
                    String normalized = raw.replace('\\', '/').replaceAll("/+$", "");
                    String path = normalized.contains("/") ? normalized : ProjectTools.modulePath(opened.project(), raw);
                    if (path.isEmpty() || !root.resolve(path).normalize().startsWith(root)) {
                        throw new ToolException("Путь вне проекта: " + raw);
                    }
                    paths.add(path);
                }
                if (paths.isEmpty()) {
                    throw new ToolException("Не указаны paths: что откатить");
                }
                CheckoutCommand checkout = git.checkout().setStartPoint("HEAD");
                paths.forEach(path -> checkout.addPath(opened.repositoryPath(path)));
                checkout.call();
            } catch (GitAPIException | IOException e) {
                throw new ToolException("git restore не выполнен: " + e.getMessage());
            } finally {
                if (!paths.isEmpty()) {
                    files.refresh(project, paths);
                }
            }
            return new Result("Возвращено к последнему коммиту (HEAD): " + String.join(", ", paths)
                    + ". Новые файлы, если были, остались — их видно в git_status.", "откачено: " + paths.size());
        }
    }

    private static List<String> paths(Map<String, Object> input) {
        Object value = input.get("paths");
        List<String> paths = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> paths.add(String.valueOf(item).strip()));
        } else if (value != null) {
            paths.add(String.valueOf(value).strip());
        }
        paths.removeIf(String::isEmpty);
        return paths;
    }

    // ---- общее ----

    /**
     * Репозиторий проекта.
     *
     * @param prefix путь проекта внутри репозитория, разделитель {@code /}; пусто — проект в корне
     */
    private record Opened(String project, Repository repository, String prefix) implements AutoCloseable {

        String repositoryPath(String projectPath) {
            return prefix.isEmpty() ? projectPath : prefix + "/" + projectPath;
        }

        /** Путь в проекте: для показа модели. */
        String projectPath(String repositoryPath) {
            return prefix.isEmpty() ? repositoryPath
                    : repositoryPath.startsWith(prefix + "/") ? repositoryPath.substring(prefix.length() + 1) : repositoryPath;
        }

        @Override
        public void close() {
            repository.close();
        }
    }

    private Opened open(Map<String, Object> input) throws ToolException {
        String project = ProjectTools.project(files, input);
        Path root = files.root(project).orElseThrow(() -> new ToolException("Каталог проекта «" + project + "» не найден"));
        return open(project, root);
    }

    /**
     * Текст файла проекта в последнем коммите — «до» для правок, сделанных
     * мимо плагина (инструментами MCP). Переводы строк — как в {@code current}.
     *
     * @param path    путь относительно проекта
     * @param current текст файла сейчас; {@code null} — файла нет
     * @return {@code null} — в последнем коммите файла нет
     * @throws ToolException проект не в git
     */
    public static String headText(String project, Path root, String path, String current) throws ToolException {
        try (Opened opened = open(project, root)) {
            Repository repository = opened.repository();
            ObjectId tree = repository.resolve("HEAD^{tree}");
            if (tree == null) {
                return null;
            }
            try (TreeWalk walk = TreeWalk.forPath(repository, opened.repositoryPath(path), tree)) {
                if (walk == null) {
                    return null;
                }
                String text = new String(repository.open(walk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) {
                    text = text.substring(1);
                }
                return current != null && current.contains("\r\n") && !text.contains("\r\n") ? text.replace("\n", "\r\n") : text;
            }
        } catch (IOException e) {
            throw new ToolException("Не удалось прочитать " + path + " из git: " + e.getMessage());
        }
    }

    private static Opened open(String project, Path root) throws ToolException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().readEnvironment().findGitDir(root.toFile());
        if (builder.getGitDir() == null) {
            throw new ToolException("Проект «" + project + "» не в git-репозитории");
        }
        try {
            Repository repository = builder.setMustExist(true).build();
            if (repository.isBare() || repository.getWorkTree() == null) {
                repository.close();
                throw new ToolException("Репозиторий проекта «" + project + "» без рабочей копии");
            }
            Path workTree = repository.getWorkTree().toPath().toRealPath();
            String prefix = workTree.relativize(root.toRealPath()).toString().replace('\\', '/');
            return new Opened(project, repository, prefix);
        } catch (IOException e) {
            throw new ToolException("Не удалось открыть git-репозиторий проекта: " + e.getMessage());
        }
    }

    private static TreeFilter filter(Opened opened, String module) throws ToolException {
        String path = module.isEmpty() ? opened.prefix() : opened.repositoryPath(ProjectTools.modulePath(opened.project(), module));
        return path.isEmpty() ? TreeFilter.ALL : PathFilter.create(path);
    }

    private static int section(List<String> lines, String title, Opened opened, Set<String> paths) {
        if (paths.isEmpty()) {
            return 0;
        }
        lines.add(title + " (" + paths.size() + "):");
        paths.stream().limit(MAX_STATUS).forEach(path -> {
            String projectPath = opened.projectPath(path);
            lines.add("  " + BslModulePath.metadataName(projectPath).map(name -> name + " (" + projectPath + ")").orElse(projectPath));
        });
        if (paths.size() > MAX_STATUS) {
            lines.add("  … и ещё " + (paths.size() - MAX_STATUS));
        }
        return paths.size();
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> all = new TreeSet<>(first);
        all.addAll(second);
        return all;
    }

    private static String head(Repository repository) throws IOException {
        String branch = repository.getBranch();
        ObjectId head = repository.resolve("HEAD");
        if (head == null) {
            return "Ветка " + branch + ", коммитов ещё нет.";
        }
        try (RevWalk walk = new RevWalk(repository)) {
            return "Ветка " + branch + ", последний коммит " + describe(walk.parseCommit(head));
        }
    }

    private static RevCommit commit(Repository repository, String commit) throws ToolException, IOException {
        ObjectId id;
        try {
            id = repository.resolve(commit + "^{commit}");
        } catch (RuntimeException e) {
            id = null;
        }
        if (id == null) {
            throw new ToolException("Коммит «" + commit + "» не найден");
        }
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit revision = walk.parseCommit(id);
            for (RevCommit parent : revision.getParents()) {
                walk.parseHeaders(parent);
            }
            return revision;
        }
    }

    private static CanonicalTreeParser tree(ObjectReader reader, ObjectId tree) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, tree);
        return parser;
    }

    /** «a1b2c3d 2026-10-28 14:05 Иванов: Исправлен расчёт скидки». */
    private static String describe(RevCommit commit) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
        return commit.abbreviate(7).name() + " " + format.format(commit.getAuthorIdent().getWhen()) + " "
                + commit.getAuthorIdent().getName() + ": " + commit.getShortMessage();
    }
}
