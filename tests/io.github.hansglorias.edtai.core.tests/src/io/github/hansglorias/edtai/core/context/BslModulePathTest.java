package io.github.hansglorias.edtai.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class BslModulePathTest {

    private static final Map<String, String> MODULES = Map.ofEntries(
            Map.entry("src/CommonModules/ОбщегоНазначения/Module.bsl", "ОбщийМодуль.ОбщегоНазначения.Модуль"),
            Map.entry("src/Catalogs/Номенклатура/ObjectModule.bsl", "Справочник.Номенклатура.МодульОбъекта"),
            Map.entry("src/Catalogs/Номенклатура/ManagerModule.bsl", "Справочник.Номенклатура.МодульМенеджера"),
            Map.entry("src/Documents/РеализацияТоваров/ObjectModule.bsl", "Документ.РеализацияТоваров.МодульОбъекта"),
            Map.entry("src/InformationRegisters/Цены/RecordSetModule.bsl", "РегистрСведений.Цены.МодульНабораЗаписей"),
            Map.entry("src/AccumulationRegisters/Остатки/RecordSetModule.bsl", "РегистрНакопления.Остатки.МодульНабораЗаписей"),
            Map.entry("src/Constants/ОсновнаяВалюта/ValueManagerModule.bsl", "Константа.ОсновнаяВалюта.МодульМенеджераЗначения"),
            Map.entry("src/Reports/Продажи/ObjectModule.bsl", "Отчет.Продажи.МодульОбъекта"),
            Map.entry("src/DataProcessors/Загрузка/ObjectModule.bsl", "Обработка.Загрузка.МодульОбъекта"),
            Map.entry("src/Enums/Статусы/ManagerModule.bsl", "Перечисление.Статусы.МодульМенеджера"),
            Map.entry("src/ChartsOfCharacteristicTypes/Свойства/ObjectModule.bsl", "ПланВидовХарактеристик.Свойства.МодульОбъекта"),
            Map.entry("src/ExchangePlans/Полный/ObjectModule.bsl", "ПланОбмена.Полный.МодульОбъекта"),
            Map.entry("src/BusinessProcesses/Задание/ObjectModule.bsl", "БизнесПроцесс.Задание.МодульОбъекта"),
            Map.entry("src/Tasks/ЗадачаИсполнителя/ManagerModule.bsl", "Задача.ЗадачаИсполнителя.МодульМенеджера"),
            Map.entry("src/CommonForms/Вопрос/Module.bsl", "ОбщаяФорма.Вопрос.Модуль"),
            Map.entry("src/CommonCommands/Печать/CommandModule.bsl", "ОбщаяКоманда.Печать.МодульКоманды"),
            Map.entry("src/HTTPServices/API/Module.bsl", "HTTPСервис.API.Модуль"),
            Map.entry("src/WebServices/Обмен/Module.bsl", "WebСервис.Обмен.Модуль"),
            Map.entry("src/Catalogs/Номенклатура/Forms/ФормаЭлемента/Module.bsl", "Справочник.Номенклатура.Форма.ФормаЭлемента.Модуль"),
            Map.entry("src/Documents/Заказ/Commands/Печать/CommandModule.bsl", "Документ.Заказ.Команда.Печать.МодульКоманды"),
            Map.entry("src/Configuration/SessionModule.bsl", "Конфигурация.МодульСеанса"),
            Map.entry("src/Configuration/ManagedApplicationModule.bsl", "Конфигурация.МодульУправляемогоПриложения"),
            Map.entry("src/Configuration/OrdinaryApplicationModule.bsl", "Конфигурация.МодульОбычногоПриложения"),
            Map.entry("src/Configuration/ExternalConnectionModule.bsl", "Конфигурация.МодульВнешнегоСоединения"),
            Map.entry("src/ExternalDataProcessors/Загрузка/ObjectModule.bsl", "ВнешняяОбработка.Загрузка.МодульОбъекта"));

    @Test
    void pathToNameAndBack() {
        MODULES.forEach((path, name) -> {
            assertEquals(Optional.of(name), BslModulePath.metadataName(path), path);
            assertEquals(Optional.of(path), BslModulePath.path(name), name);
        });
    }

    @Test
    void acceptsWindowsSeparatorsAndLeadingSlash() {
        assertEquals(Optional.of("ОбщийМодуль.Общий.Модуль"), BslModulePath.metadataName("src\\CommonModules\\Общий\\Module.bsl"));
        assertEquals(Optional.of("ОбщийМодуль.Общий.Модуль"), BslModulePath.metadataName("/src/CommonModules/Общий/Module.bsl"));
    }

    @Test
    void unknownPathsHaveNoName() {
        for (String path : new String[] { "Module.bsl", "src/Catalogs/Номенклатура/Номенклатура.mdo", "src/Неизвестно/Х/Module.bsl",
                "src/Catalogs/Номенклатура/Templates/Макет/Template.txt", "lib/CommonModules/Общий/Module.bsl",
                "src/Configuration/Configuration.mdo", "src/Catalogs/Номенклатура/Forms/Форма/Form.form" }) {
            assertEquals(Optional.empty(), BslModulePath.metadataName(path), path);
        }
    }

    @Test
    void nameInEnglishAnyCaseAndWithYo() {
        assertEquals(Optional.of("src/CommonModules/ОбщегоНазначения/Module.bsl"), BslModulePath.path("CommonModule.ОбщегоНазначения"));
        assertEquals(Optional.of("src/Catalogs/Товары/ManagerModule.bsl"), BslModulePath.path("catalog.Товары.managermodule"));
        assertEquals(Optional.of("src/Reports/Продажи/ObjectModule.bsl"), BslModulePath.path("Отчёт.Продажи"));
        assertEquals(Optional.of("src/Configuration/SessionModule.bsl"), BslModulePath.path("Configuration.SessionModule"));
        assertEquals(Optional.of("src/Catalogs/Товары/Forms/ФормаСписка/Module.bsl"), BslModulePath.path("Справочник.Товары.Форма.ФормаСписка"));
    }

    @Test
    void nameWithoutModuleMeansMainModule() {
        assertEquals(Optional.of("src/CommonModules/Общий/Module.bsl"), BslModulePath.path("ОбщийМодуль.Общий"));
        assertEquals(Optional.of("src/Documents/Заказ/ObjectModule.bsl"), BslModulePath.path("Документ.Заказ"));
        assertEquals(Optional.of("src/InformationRegisters/Цены/RecordSetModule.bsl"), BslModulePath.path("РегистрСведений.Цены"));
        assertEquals(Optional.of("src/CommonCommands/Печать/CommandModule.bsl"), BslModulePath.path("ОбщаяКоманда.Печать"));
    }

    @Test
    void unknownNames() {
        for (String name : new String[] { "", "Справочник", "Неизвестно.Х", "Справочник.Х.Неизвестно", "Справочник..Модуль",
                "Справочник.Х.Макет.Y", "Конфигурация.Модуль" }) {
            assertEquals(Optional.empty(), BslModulePath.path(name), name);
        }
    }
}
