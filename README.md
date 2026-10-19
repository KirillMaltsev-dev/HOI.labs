# J1 — сервер генерации ключей

Лабораторная по хранению и обработке информации. [Условие](docs/task_J1.pdf).
Нужен JDK 21 или новее; Maven запускается через включённый wrapper.

Сервер принимает по TCP ASCII-имя с нулевым байтом в конце, создаёт пару RSA-8192
и сертификат X.509. Для одного имени, включая одновременные запросы, создаётся
одна пара; успешные результаты хранятся в оперативной памяти до остановки сервера.
После перезапуска кеш пуст, а ключ подписи можно использовать прежний

## Сборка и тесты

Windows PowerShell:

```powershell
.\mvnw.cmd clean test
```

Linux/macOS:

```sh
./mvnw clean test
```

Полная проверка включает настоящую генерацию RSA-8192 и может занять несколько
минут. `ServerEndToEndTest` запускает сервер и CLI-клиентов отдельными JVM,
открывает 100 TCP-соединений, проверяет половинное закрытие и RST, задержку чтения,
отключение без ответа, выдачу одинакового материала для одного имени и разных
ключей для разных имён. Проверяются сохранённые PEM-файлы, Subject, Issuer,
размер ключей и подпись сертификата загруженным ключом сервера
Ключи, ответы и журналы этого теста создаются во временном каталоге JUnit

Другие тесты отдельно проверяют частичное чтение/запись, нулевую запись,
свободную работу генераторов при медленном получателе, конкурентность кеша и ошибки

## Запуск в Windows

Из корня проекта после сборки подготовить classpath и ключ подписи:

```powershell
.\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" "-DincludeScope=runtime"
$j1Classpath = "target/classes;" + (Get-Content -LiteralPath target/runtime-classpath.txt -Raw).Trim()
java -cp $j1Classpath ru.nsu.fit.maltsev.hoi.crypto.SigningKeyTool --output keys/signing.key
```

Ключ создаётся один раз. Если `keys/signing.key` уже существует, пропустить
последнюю команду: утилита не перезаписывает ключи. RSA всегда использует 8192 бита.

Запустить сервер:

```powershell
java -cp $j1Classpath ru.nsu.fit.maltsev.hoi.server.ServerMain --port 8080 --workers 4 --signing-key keys/signing.key --issuer "CN=J1 Issuer,O=NSU"
```

В другом PowerShell из корня проекта заново задать `$j1Classpath` и вызвать клиента:

```powershell
$j1Classpath = "target/classes;" + (Get-Content -LiteralPath target/runtime-classpath.txt -Raw).Trim()
java -cp $j1Classpath ru.nsu.fit.maltsev.hoi.client.ClientMain --name Ann --host localhost --port 8080 --output-dir client-output/first
java -cp $j1Classpath ru.nsu.fit.maltsev.hoi.client.ClientMain --name Ann --host localhost --port 8080 --output-dir client-output/cached
java -cp $j1Classpath ru.nsu.fit.maltsev.hoi.client.ClientMain --name Ann --host localhost --port 8080 --read-delay-seconds 5 --output-dir client-output/slow
java -cp $j1Classpath ru.nsu.fit.maltsev.hoi.client.ClientMain --name Ann --host localhost --port 8080 --disconnect-without-read
```

В каталогах `first`, `cached` и `slow` должны появиться одинаковые `.key` и `.crt`
Имена файлов имеют вид `client-<SHA-256 имени>.key` и `.crt`
Флаг отключения без чтения не создаёт файлов. Существующие файлы не
перезаписываются; для повторного запуска выбрать новый выходной каталог

Сравнить SHA-256 файлов:

```powershell
Get-ChildItem -LiteralPath client-output/first,client-output/cached,client-output/slow -File | Get-FileHash -Algorithm SHA256
```

Совпадать должны отдельно хеши всех `.key` и отдельно хеши всех `.crt`
Для другого имени, например `Bob`, ключ должен отличаться

## Запуск в Linux/macOS

```sh
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt -DincludeScope=runtime
j1_classpath="target/classes:$(cat target/runtime-classpath.txt)"
java -cp "$j1_classpath" ru.nsu.fit.maltsev.hoi.crypto.SigningKeyTool --output keys/signing.key
java -cp "$j1_classpath" ru.nsu.fit.maltsev.hoi.server.ServerMain --port 8080 --workers 4 --signing-key keys/signing.key --issuer "CN=J1 Issuer,O=NSU"
```

В другом терминале задать тот же `j1_classpath` и запустить:

```sh
java -cp "$j1_classpath" ru.nsu.fit.maltsev.hoi.client.ClientMain --name Ann --host localhost --port 8080
```

Опции клиента совпадают с Windows. Справка доступна через `ServerMain --help`
и `ClientMain --help` с тем же classpath. Сервер останавливается через Ctrl+C

## Проверка с другого компьютера

Сервер по умолчанию слушает `0.0.0.0`. На клиентском компьютере собрать проект
и передать `--host <IPv4-адрес-сервера>` вместо `localhost`; ключ подписи нужен
только серверу. Входящий TCP-порт сервера должен быть разрешён в межсетевом экране
Повторить обычный запрос, задержку чтения и отключение без чтения
Автотесты используют loopback и не подтверждают доступность через внешнюю сеть

`--workers` задаёт положительное число генерирующих потоков без ограничения числом
ядер. Для проверки можно запустить сервер с числом работников больше числа
аппаратных потоков компьютера

## Устройство и ограничения

Один поток Selector обслуживает соединения; фиксированный пул выполняет генерацию,
подпись и подготовку PEM. Результаты передаются через очередь с `selector.wakeup()`
Генерирующие потоки не выполняют сетевую запись. Публичный ключ передаётся внутри
сертификата `.crt`, приватный — в PKCS#8 PEM `.key`

Текущий протокол принимает 1–255 печатных ASCII-байт в имени. Регистр и пробелы
значимы, Subject содержит буквальное имя в CN. Очередь заданий и кеш не ограничены;
для лабораторной результаты сохраняются до остановки процесса. Клиент проверяет
соответствие ключа сертификату, но не доверие к Issuer: его публичный ключ не
передаётся в протоколе. Проверка подписи Issuer выполняется тестами
