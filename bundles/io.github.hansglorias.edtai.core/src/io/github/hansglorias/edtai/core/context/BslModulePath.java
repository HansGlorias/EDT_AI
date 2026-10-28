package io.github.hansglorias.edtai.core.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Имя модуля в нотации 1С по пути файла в проекте EDT и обратно:
 * {@code src/Catalogs/Номенклатура/ObjectModule.bsl} ↔
 * «Справочник.Номенклатура.МодульОбъекта».
 *
 * <p>API EDT в target platform нет, поэтому объект метаданных выводим из
 * структуры каталогов: {@code src/<Тип>/<Имя>/[Forms|Commands/<Имя>/]<Модуль>.bsl}
 * и {@code src/Configuration/<Модуль>.bsl}.
 */
public final class BslModulePath {

    /** Тип объекта: каталог в {@code src}, имя в 1С по-русски и по-английски. */
    private record Type(String directory, String russian, String english) {
    }

    private record Module(String file, String russian) {
    }

    private static final List<Type> TYPES = List.of(
            new Type("CommonModules", "ОбщийМодуль", "CommonModule"),
            new Type("Catalogs", "Справочник", "Catalog"),
            new Type("Documents", "Документ", "Document"),
            new Type("DocumentJournals", "ЖурналДокументов", "DocumentJournal"),
            new Type("Enums", "Перечисление", "Enum"),
            new Type("Reports", "Отчет", "Report"),
            new Type("DataProcessors", "Обработка", "DataProcessor"),
            new Type("ChartsOfCharacteristicTypes", "ПланВидовХарактеристик", "ChartOfCharacteristicTypes"),
            new Type("ChartsOfAccounts", "ПланСчетов", "ChartOfAccounts"),
            new Type("ChartsOfCalculationTypes", "ПланВидовРасчета", "ChartOfCalculationTypes"),
            new Type("InformationRegisters", "РегистрСведений", "InformationRegister"),
            new Type("AccumulationRegisters", "РегистрНакопления", "AccumulationRegister"),
            new Type("AccountingRegisters", "РегистрБухгалтерии", "AccountingRegister"),
            new Type("CalculationRegisters", "РегистрРасчета", "CalculationRegister"),
            new Type("BusinessProcesses", "БизнесПроцесс", "BusinessProcess"),
            new Type("Tasks", "Задача", "Task"),
            new Type("ExchangePlans", "ПланОбмена", "ExchangePlan"),
            new Type("Constants", "Константа", "Constant"),
            new Type("Sequences", "Последовательность", "Sequence"),
            new Type("FilterCriteria", "КритерийОтбора", "FilterCriterion"),
            new Type("SettingsStorages", "ХранилищеНастроек", "SettingsStorage"),
            new Type("CommonForms", "ОбщаяФорма", "CommonForm"),
            new Type("CommonCommands", "ОбщаяКоманда", "CommonCommand"),
            new Type("WebServices", "WebСервис", "WebService"),
            new Type("HTTPServices", "HTTPСервис", "HTTPService"),
            new Type("ExternalDataProcessors", "ВнешняяОбработка", "ExternalDataProcessor"),
            new Type("ExternalReports", "ВнешнийОтчет", "ExternalReport"));

    private static final List<Module> MODULES = List.of(
            new Module("Module.bsl", "Модуль"),
            new Module("ObjectModule.bsl", "МодульОбъекта"),
            new Module("ManagerModule.bsl", "МодульМенеджера"),
            new Module("RecordSetModule.bsl", "МодульНабораЗаписей"),
            new Module("ValueManagerModule.bsl", "МодульМенеджераЗначения"),
            new Module("CommandModule.bsl", "МодульКоманды"));

    private static final List<Module> CONFIGURATION_MODULES = List.of(
            new Module("SessionModule.bsl", "МодульСеанса"),
            new Module("ManagedApplicationModule.bsl", "МодульУправляемогоПриложения"),
            new Module("OrdinaryApplicationModule.bsl", "МодульОбычногоПриложения"),
            new Module("ExternalConnectionModule.bsl", "МодульВнешнегоСоединения"));

    private static final String CONFIGURATION = "Конфигурация";
    private static final String FORM = "Форма";
    private static final String COMMAND = "Команда";

    private BslModulePath() {
    }

    /**
     * @param path путь файла относительно проекта, разделитель {@code /} или {@code \}
     * @return имя модуля, например «ОбщийМодуль.ОбщегоНазначения.Модуль»; пусто, если путь не похож на модуль EDT
     */
    public static Optional<String> metadataName(String path) {
        String[] parts = path.replace('\\', '/').replaceAll("^/+", "").split("/");
        if (parts.length < 3 || !parts[0].equals("src")) {
            return Optional.empty();
        }
        if (parts.length == 3 && parts[1].equals("Configuration")) {
            return module(CONFIGURATION_MODULES, parts[2]).map(module -> CONFIGURATION + "." + module.russian());
        }
        Optional<Type> type = TYPES.stream().filter(candidate -> candidate.directory().equals(parts[1])).findFirst();
        if (type.isEmpty()) {
            return Optional.empty();
        }
        String object = type.get().russian() + "." + parts[2];
        if (parts.length == 4) {
            return module(MODULES, parts[3]).map(module -> object + "." + module.russian());
        }
        if (parts.length == 6 && (parts[3].equals("Forms") || parts[3].equals("Commands"))) {
            String nested = parts[3].equals("Forms") ? FORM : COMMAND;
            return module(MODULES, parts[5]).map(module -> object + "." + nested + "." + parts[4] + "." + module.russian());
        }
        return Optional.empty();
    }

    /**
     * Обратное преобразование. Имя типа и модуля — по-русски или
     * по-английски, регистр и «ё» не важны. Без имени модуля — модуль
     * общего модуля, общей формы, сервиса, а для остальных — модуль объекта.
     *
     * @param name например «Справочник.Номенклатура.МодульОбъекта» или «CommonModule.ОбщегоНазначения»
     * @return путь относительно проекта; пусто, если имя не распознано
     */
    public static Optional<String> path(String name) {
        List<String> parts = new ArrayList<>(List.of(name.strip().split("\\.", -1)));
        if (parts.stream().anyMatch(String::isBlank)) {
            return Optional.empty();
        }
        if (parts.size() == 2 && same(parts.get(0), CONFIGURATION, "Configuration")) {
            return configurationModule(parts.get(1)).map(file -> "src/Configuration/" + file);
        }
        if (parts.size() < 2) {
            return Optional.empty();
        }
        Optional<Type> type = TYPES.stream().filter(candidate -> same(parts.get(0), candidate.russian(), candidate.english())).findFirst();
        if (type.isEmpty()) {
            return Optional.empty();
        }
        String object = "src/" + type.get().directory() + "/" + parts.get(1);
        if (parts.size() == 2) {
            return Optional.of(object + "/" + defaultModule(type.get()));
        }
        if (parts.size() == 3) {
            return moduleFile(parts.get(2)).map(file -> object + "/" + file);
        }
        if (parts.size() == 4 || parts.size() == 5) {
            String nested;
            String fallback;
            if (same(parts.get(2), FORM, "Form")) {
                nested = "Forms";
                fallback = "Module.bsl";
            } else if (same(parts.get(2), COMMAND, "Command")) {
                nested = "Commands";
                fallback = "CommandModule.bsl";
            } else {
                return Optional.empty();
            }
            Optional<String> file = parts.size() == 5 ? moduleFile(parts.get(4)) : Optional.of(fallback);
            return file.map(module -> object + "/" + nested + "/" + parts.get(3) + "/" + module);
        }
        return Optional.empty();
    }

    /** Каталоги типов в {@code src} по порядку: «CommonModules», «Catalogs»… */
    public static List<String> typeDirectories() {
        return TYPES.stream().map(Type::directory).toList();
    }

    /** «Catalogs» → «Справочник»; пусто, если каталог не тип метаданных. */
    public static Optional<String> typeName(String directory) {
        return TYPES.stream().filter(type -> type.directory().equals(directory)).map(Type::russian).findFirst();
    }

    /**
     * Каталог типа по имени: «Справочник», «Catalog», «Catalogs», регистр и «ё» не важны.
     */
    public static Optional<String> typeDirectory(String name) {
        return TYPES.stream()
                .filter(type -> same(name, type.russian(), type.english()) || normalize(name).equals(normalize(type.directory())))
                .map(Type::directory).findFirst();
    }

    private static String defaultModule(Type type) {
        return switch (type.directory()) {
            case "CommonModules", "CommonForms", "WebServices", "HTTPServices" -> "Module.bsl";
            case "CommonCommands" -> "CommandModule.bsl";
            case "InformationRegisters", "AccumulationRegisters", "AccountingRegisters", "CalculationRegisters", "Sequences" ->
                    "RecordSetModule.bsl";
            case "Constants" -> "ValueManagerModule.bsl";
            case "DocumentJournals", "Enums", "FilterCriteria", "SettingsStorages" -> "ManagerModule.bsl";
            default -> "ObjectModule.bsl";
        };
    }

    private static Optional<Module> module(List<Module> modules, String file) {
        return modules.stream().filter(module -> module.file().equals(file)).findFirst();
    }

    private static Optional<String> moduleFile(String name) {
        return MODULES.stream().filter(module -> same(name, module.russian(), english(module))).map(Module::file).findFirst();
    }

    private static Optional<String> configurationModule(String name) {
        return CONFIGURATION_MODULES.stream().filter(module -> same(name, module.russian(), english(module)))
                .map(Module::file).findFirst();
    }

    private static String english(Module module) {
        return module.file().substring(0, module.file().length() - ".bsl".length());
    }

    private static boolean same(String value, String russian, String english) {
        String normalized = normalize(value);
        return normalized.equals(normalize(russian)) || normalized.equals(normalize(english));
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
