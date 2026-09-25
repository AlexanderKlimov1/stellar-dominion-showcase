# Переименование: игра, расы, лидеры

**Применено 22.09.2026: имена рас и лидеров из столбца «стало» лежат в справочниках**
(`resources/Races/race-traits.json`, `resources/Leaders/leaders.json`) — на обоих языках,
вместе со званиями. Название игры (Stellar Dominion) применено раньше. Осталось
нерешённым то, что перечислено в конце файла: особая звезда и названия технологий с
компонентами. Таблицы ниже оставлены как есть: по ним видно, что чем заменено, и запасные
варианты никуда не делись.

Предложения к замене имён, взятых у Master of Orion II. Правьте прямо здесь; в
справочники (`resources/Races/race-traits.json`, `resources/Leaders/leaders.json`) и в
код ложится то, что останется в столбце «стало» после вашей правки. Коды (`PSILONS`,
`kirsus`) — внутренние идентификаторы, игроку не видны, их не трогаем: на них ссылаются
базы партий.

Английский теперь — язык игры по умолчанию, поэтому имена даны по-английски; русская
форма — для русской локали.

## Название игры

Условие: не «Master of Orion» (действующий товарный знак) и без «3». Перед выбором
финалиста — проверить по поиску Steam и по базам товарных знаков (WIPO Global Brand
Database, Роспатент): имя не должно совпадать с уже вышедшей игрой.

| # | Название | Почему |
|---|---|---|
| 1 | **Stellar Dominion** | прямо о жанре: господство над звёздами; читается на обоих языках («Звёздный доминион») |
| 2 | **Astral Thrones** | восемь империй, восемь тронов; коротко, звучно |
| 3 | **Sovereign Skies** | «Суверенные небеса»; мягче, чем «доминион» |
| 4 | **Diadem of Stars** | образ короны из звёзд — Высший совет и победа избранием |
| 5 | **Void Regents** | регенты пустоты; необычное слово, легко ищется |
| 6 | **Helion Reach** | вымышленное имя сектора («Предел Гелиона»); растёт в вселенную, а не в один заголовок |
| 7 | **New Worlds Directive** | эхо нынешнего подзаголовка «Direction New World», без чужого имени |
| 8 | **Crown of Nebulae** | «Корона туманностей» |
| 9 | **Starfall Sovereigns** | аллитерация; «государи звездопада» |
| 10 | **Eight Suns** | по числу империй; самое короткое и самое легко запоминаемое |

Мой выбор — 1 или 6: первое сразу говорит, что это 4X, второе даёт имя миру, из которого
потом выводятся названия рас, лидеров и особых звёзд.

## Расы

Характер расы сохраняется — он в сторонах (`traits`) и описании; меняется только имя.
Второй вариант — запасной.

| Код | Было | Стало | Запас | Русская форма |
|---|---|---|---|---|
| HUMANS | Human | **Earthlings** | Solari | земляне |
| PSILONS | Psilon | **Cerebri** | Noesari | церебри |
| KLACKONS | Klackon | **Chitarri** | Hivekin | читарри |
| SILICOIDS | Silicoid | **Lithari** | Petrans | литари |
| MRRSHAN | Mrrshan | **Felyr** | Vashari | фелиры |
| BULRATHI | Bulrathi | **Ursani** | Grothar | урсани |
| SAKKRA | Sakkra | **Saurath** | Vexid | саураты |
| DARLOKS | Darlok | **Veylin** | Umbrani | вейлины |
| ALKARI | Alkari | **Aviari** | Skyrren | авиары |
| ELERIAN | Elerian | **Iluni** | Seerkin | илуни |
| GNOLAM | Gnolam | **Gildrin** | Quillim | гилдрины |
| MEKLAR | Meklar | **Synthar** | Ferrocyte | синтары |
| TRILARIAN | Trilarian | **Tidari** | Abyssari | тидари |

Коды в файле могут отличаться от приведённых (проверить по `race-traits.json`);
таблица про имена.

## Лидеры

Звания, способности, опыт и цены не меняются. Титулы, привязанные к расе, следуют за
новым именем расы; титулы с чужой вселенной («Последний орионец», «Антаранский воин»,
«Тулосианский наёмник», «Икарианский врач») переписаны под свою — «Стражи», «Предел»,
«окраина».

### Колонии

| Код | Было | Стало | Титул (en) | Титул (ru) |
|---|---|---|---|---|
| ailis | Ailis | **Ilsera** | Gifted | Одарённая |
| androgena | Androgena | **Ostrava** | Administrator | Управительница |
| black-razor | Black Razor | **Vantrell Sable** | Punisher | Каратель |
| brainac | Brainac | **Quorrin** | Unpredictable | Непредсказуемый |
| cassandra | Cassandra | **Nyssira** | Iluni Seer | Провидица илуни |
| chug | Chug | **Borrik** | Planetologist | Планетолог |
| claw | Claw | **Kzarr** | Chitarri Overseer | Надсмотрщик читарри |
| crassis | Crassis | **Meridon** | Manager | Управляющий |
| draxx | Draxx | **Sethrane** | Spymaster | Мастер шпионажа |
| electra | Electra | **Veloria** | High Priestess | Верховная жрица |
| emo | Emo | **Tessil** | Scientist | Учёный |
| felina | Felina | **Maribeth Sol** | Naturalist | Натуралистка |
| galis | Galis | **Aurelio Vance** | Financier | Финансист |
| garron | Garron | **Hallam Dree** | Ambassador | Посол |
| grogg | Grogg | **Pipkin Vale** | Gildrin Dealer | Делец гилдринов |
| houri | Houri | **Senna Wyld** | Ecologist | Эколог |
| khunagg | Khunagg | **Dromagh** | Ruthless | Безжалостный |
| kimbuzzi | Kimbuzzi | **Tobbe Marrow** | Farmer | Земледелец |
| kirsus | Kirsus | **Ilvan Thess** | Cerebri Scholar | Учёный церебри |
| lydon | Lydon | **Cassius Orme** | Aristocrat | Аристократ |
| malovane | Malovane | **Harrowgate** | Warlord | Полководец |
| matrix | Matrix | **Cipherine** | Cybermancer | Киберманс |
| megatron | Megatron | **Kalibrax** | Ancient Android | Древний андроид |
| mentox | Mentox | **Osgrim Vell** | Legendary Scientist | Легендарный учёный |
| necron | Necron | **Morvain** | Dark Overlord | Тёмный властелин |
| nimraaz | Nimraaz | **Tarquel** | Master Tactician | Мастер тактики |
| orphus | Orphus | **Elowen Pax** | Peacemaker | Миротворец |
| ralleia | Ralleia | **Lyssandre** | Siren | Сирена |
| rash-iki | Rash-Iki | **Kor-Vashti** | Warleader | Военачальник |
| tanus | Tanus | **Rennick Ash** | Revolutionary | Революционер |
| torg | Torg | **Ulgrath** | Sovereign | Повелитель |
| urro | Urro | **Fennoril** | Physician of the Outer Reach | Врач с Окраины |
| valin | Valin | **Dashiel Rook** | Free Trader | Вольный торговец |
| vott | Vott | **Grellith** | Technomancer | Техномант |
| xantus | Xantus | **Oberon Vaal** | High Sovereign | Верховный правитель |
| yota | Yota | **Old Wenmoor** | Old Mentor | Старый наставник |

### Корабли

| Код | Было | Стало | Титул (en) | Титул (ru) |
|---|---|---|---|---|
| altos | Altos | **Skerrin** | Aviari Pilot | Пилот-авиар |
| aquasarrious | Aquasarrious | **Thalassa Nir** | Tidari Navigator | Навигатор тидари |
| caern | Caern | **Ferrand Kell** | Outland Mercenary | Наёмник с окраин |
| cyr | Cyr | **Jax Ivory** | Fighter Ace | Ас-истребитель |
| dalan | Dalan | **Corvin Halloway** | Legendary Captain | Легендарный капитан |
| dantos | Dantos | **Rusk Maddox** | Raider Chief | Предводитель разбойников |
| diablo | Diablo | **Scourgewire** | Cyber-marauder | Кибермародёр |
| gizmo | Gizmo | **Tinker Vosk** | Inventor | Изобретатель |
| grak | Grak | **Brommak** | Ursani Commando | Коммандос урсани |
| grum | Grum | **Halden Ironmark** | Armorer | Оружейник |
| hawk | Hawk | **Selvan Reach** | Astrogator | Астрогатор |
| jarred | Jarred | **Emory Stride** | Pathfinder | Первопроходец |
| karg | Karg | **Redmane Krull** | Pirate Captain | Пиратский капитан |
| kher | Kher | **Slick Marlowe** | Smuggler | Контрабандист |
| kronos | Kronos | **Aeonel** | Ancient Wanderer | Древний странник |
| kytryl | Kytryl | **Vesper Quill** | Privateer | Капер |
| loknar | Loknar | **Ashkarel** | Last of the Wardens | Последний из Стражей |
| mukirr | Mukirr | **Rrsha Vane** | Felyr Warrior | Воитель фелиров |
| nhagg | Nhagg | **Gorrund** | Weapons Master | Мастер вооружений |
| nile | Nile | **Tamsin Ferro** | Outcast Warrior | Отверженный воин |
| ruola | Ruola | **Brisa Halloran** | Gunner | Канонир |
| skaine | Skaine | **Kaelith Roan** | Legendary Pilot | Легендарный пилот |
| slag | Slag | **Mordecai Flint** | Arms Dealer | Торговец оружием |
| slith | Slith | **Zeph Arrow** | Rebel Pilot | Пилот-повстанец |
| sparky | Sparky | **Unit Lumen** | Synthar Cybernaut | Кибернавт синтаров |
| tellik | Tellik | **Ada Wrenfield** | Legendary Engineer | Легендарный инженер |
| tulock | Tulock | **Jonah Creed** | Bounty Hunter | Охотник за головами |
| tyranous | Tyranous | **Ssathrek** | Saurath Armorer | Оружейник сауратов |
| vlarr | V'Larr | **Nadia Verne** | Independent Trader | Независимый торговец |
| xyphys | Xyphys | **Zorvath** | Warrior from Beyond | Воин из-за Предела |

## Технологии и компоненты: что переименовывать

Список составлен 22.09.2026 по решению хозяина проекта («сначала список, потом правка»).
Разобраны все 188 технологий (`resources/Technologies/tech.json`) и 76 корпусов,
компонентов и модификаций (`resources/Ships/ship-components.json`).

**Применено в тот же день: разряды А и Б переименованы целиком**, на обоих языках, в трёх
справочниках сразу (одна вещь зовётся в нескольких: технология «Zortrium Armor» открывает
компонент того же имени, «Recyclotron» — это и технология, и здание). Заодно поправлены
описания, где имя повторялось словами, ярлыки перечислений (`GroundCombatTech`, `FuelTech`),
подписи в коде и справочная строка о дальности на экране «Инфо». Две строки, оставленные в
списке на усмотрение, решены так: **Artemis System Net → Hunters' Net** (раз уж звезда Orion
переименована, грекам в игре делать нечего), **Jump Gate оставлен** — слово стало общим.

Коды не менялись: `zortrium-armor` у корриевой брони, `doom-star` у левиафана, `recyclotron`
у переработчика. Расхождение намеренное и объяснено в самих файлах — код лежит в базе у
изученного и в ссылках справочников, а имя свободно.

**Препятствие снято 22.09.2026: коды технологий вынесены в сам файл** (`"code"` в каждой
записи `tech.json`, значения — ровно те, что выводились из названий, так что ни одна старая
партия правки не заметила). Теперь переименование технологии — это только имя: код,
изученное в базе и ссылки других справочников остаются на месте. Ссылки внутри файла тоже
перестали держаться на названиях (`recommended` — коды, `starting_levels` — «раздел:номер»),
а справочник проверяет их при чтении и отказывается читать файл с неверной ссылкой.

Значит, строки ниже можно править как обычный перевод — менять `"name"` и не трогать
`"code"`.

### А. Придумано в MOO II

| Что | Где | Предложение (en) | Русская форма |
|---|---|---|---|
| Zortrium Armor | броня, лестница | **Korrium Armor** | корриевая броня |
| Merculite Missile | ракеты | **Vulcanite Missile** | вулканитовая ракета |
| Pulson Missile | ракеты | **Pulsewave Missile** | пульсовая ракета |
| Zeon Missile | ракеты | **Aeon Missile** | эоновая ракета |
| Uridium Fuel Cells | топливо | **Ytterbium Fuel Cells** | иттербиевые элементы |
| Phasor / Phasors / Phasor Rifle | оружие, три записи | **Resonator Cannon / Resonators / Resonator Rifle** | резонаторная пушка, винтовка |
| Mauler Device | оружие | **Pulverizer** | Дробитель |
| Stellar Converter | оружие | **Nova Lance** | Копьё новы |
| Doom Star (корпус и технология) | корпуса | **Leviathan** | Левиафан |
| Battleoids | наземный бой | **Battle Walkers** | боевые шагоходы |
| Megafluxers | корпус | **Compact Frames** | компактные рамы |
| Microlite Construction | корпус | **Lightframe Construction** | лёгкие корпуса |
| Recyclotron | здание | **Reclaimer Plant** | Переработчик |
| Autolab | здание | **Automated Lab** | Автоматическая лаборатория |
| Cybertronic Computer | прицел | **Neurotronic Computer** | нейротронный компьютер |
| Dauntless Guidance System | ракеты | **Steadfast Guidance System** | система наведения «Стойкость» |
| Rangemaster Targeting Unit | прицел | **Longsight Targeting Unit** | прицельный блок «Дальновид» |
| Achilles Targeting Unit | прицел | **Weakpoint Targeting Unit** | прицельный блок «Уязвимость» |
| Hyper-X Capacitors | оружие | **Surge Capacitors** | импульсные накопители |
| Time Warp Facilitator | физика | **Temporal Accelerator** | ускоритель времени |
| Gyro Destabilizer | поля | **Spin Destabilizer** | раскручиватель |
| Displacement Device | поля | **Blink Field** | поле смещения |
| Bio Terminator | биология | **Bioplague** | биочума |
| Pleasure Dome | здание | **Elysium Dome** | Купол Элизия |
| Artemis System Net | минное поле | оставить или **Hunters' Net** | Сеть охотников |

Про Artemis: имя греческое, а не выдуманное MOO, — тот же случай, что со звездой Orion. Но
и там решение оказалось «переименовать», поэтому строка оставлена на ваше усмотрение.

### Б. Слова чужих вселенных

Эти пришли в MOO II со стороны, и с ними осторожнее, чем с самим MOO.

| Что | Откуда | Предложение (en) | Русская форма |
|---|---|---|---|
| Adamantium Armor | Marvel (сам корень «adamant» древнегреческий) | **Adamant Armor** | адамантовая броня |
| Tritanium Armor | Star Trek | **Duranite Armor** | дуранитовая броня |
| Phasor | Star Trek (phaser) | см. строку выше в разряде А | |
| Transporters | Star Trek | **Matter Transmitters** | телепорты |
| Food Replicators | Star Trek | **Matter Synthesizers** | синтезаторы пищи |
| Star Gate | Stargate | **Void Gate** | Врата пустоты |
| Jump Gate | Babylon 5, но слово стало общим | оставить | |

### В. Оставить

Настоящие вещества и физика: Titanium, Neutronium, Deuterium, Iridium, Thorium, Gauss,
Graviton, Tachyon, Neutron, Plasma, Ion, Anti-Matter, Positronic, Optronic. Общие
инженерные и жанровые слова: Automated Factory, Research Laboratory, Supercomputer,
Space Port, Stock Exchange, Battle Pods, Troop Pods, Survival Pods, Death Spores, Class I…X
Shield, Holo Simulator, Plasma Web, Biomorphic Fungi, Android Farmers/Workers/Scientists,
Gaia Transformation (миф), Psionics, Terraforming. Они встречаются в десятках игр и книг и
ни на чей мир не указывают.

## Что ещё носит чужое имя

* ~~Особая звезда **Orion** со Стражем~~ — **сделано 22.09.2026: звезда зовётся
  Wardenhold** (`StarNameCatalog.SPECIAL_STAR`). Решение хозяина проекта: само имя Orion
  астрономическое, но историю о страже этой звезды оно тянуло за собой. Имя латинское на
  обоих языках, как и прочие звёзды; партии, начатые раньше, доигрываются со звездой Orion —
  имя лежит в их базе. **Страж поставлен на Wardenhold** (решение хозяина проекта, 22.09.2026),
  и за ним лежит клад: первой империи, которая там поселится, достаются три технологии даром —
  `SpecialStarReward`. Имя звезды после этого говорит о том, что в ней и есть.
* Специфичные для MOO названия технологий и компонентов — отдельный список после
  решения по именам; обычные инженерные термины (Automated Factory, Research Laboratory)
  остаются.
* **Сделано попутно:** «Антаранцы» ушли из двух мест, которые видит игрок, — из стороны
  расы «Везучие» и из описания технологии Dimensional Portal. Вместо них лор этого плана:
  «налётчики из-за Предела» и «дорога в миры из-за Предела», в пару к Ashkarel
  («Последний из Стражей») и Zorvath («Воин из-за Предела»). Звезда переименована в тот же
  день — см. первый пункт этого списка.
