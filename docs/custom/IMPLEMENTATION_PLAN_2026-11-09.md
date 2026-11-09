# Plan wykonawczy: własna bramka SMS CRD (fork Android SMS Gateway)

**Status:** PLAN / bez wdrożenia i bez wysyłki.
**Data:** 2026-11-09. **Repo:** ryba-22/android-sms-gateway.
**Punkt wyjścia:** docs/custom/ROADMAP.md, docs/custom/CHECKPOINT.md, zgłoszenia #2–#5.
**Bazowy snapshot kodu:** adaa6c4 (upstream z 2026-09-30), gałąź planistyczna 3224d53.
**Główna decyzja:** wykorzystać istniejący Android Local Server, Room, WorkManager i SmsManager. Rozszerzyć model zachowania, nie pisać nowego gatewaya od zera.

## A. Problem i kryteria sukcesu

Rzeczywista potrzeba: regularne kampanie ok. 1100 SMS rozłożone na wiele dni oraz wiadomości doraźne ok. 10–50, przekazywane przez CRD, SMSApp, Excel i n8n. Telefon z SIM jest fizycznym nadajnikiem.

Z poprzedniego systemu mamy potwierdzone ryzyka:
- Kolejki przyjęte do API mogą zostać fizycznie obsłużone później, również po ponownym połączeniu telefonu.
- W XML telefonu z kampanii październikowej 1020/1645 wpisów rozliczeń ma znaczniki czasu poza 08:00–21:00. To nie są wiarygodne znaczniki momentu fizycznej emisji ani dowód doręczenia, ale wystarczają do odrzucenia obecnego mechanizmu jako gwarancji godzin.
- Wystąpiły 73 podwójne wpisy dla tych samych identyfikatorów, z czego 72 po operacji recovery. Brak w API nie oznacza, że SMS nie był wysłany.
- Akceptacja API, kolejka Androida, wywołanie SmsManager, callback nadania i raport doręczenia to **różne fakty**.

**Cel P0:** urządzenie nigdy nie rozpoczyna nowej próby wywołania API SmsManager, kiedy jego obowiązująca polityka zabrania wysyłania. Obejmuje SMS tekstowe, multipart, data SMS, MMS, wielu odbiorców, retry, worker, FCM, schedule i każdy priorytet.

**Cel P1:** po timeout, awarii lub utracie callbacku system nie ponawia automatycznie próby, która mogła fizycznie zostać rozpoczęta.

**Cel operacyjny:** potrafimy odtworzyć historię zlecenia oraz oddzielić stan logiczny od stanów transportu/Androida.

**Granica gwarancji:** nie możemy zagwarantować odbioru SMS przed 21:00 ani anulowania operacji już przyjętej przez modem/operatora. Gwarantujemy brak rozpoczynania nowych prób po stronie naszego kodu w niedozwolonym oknie, pod warunkiem poprawnego zegara urządzenia i egzekwowania polityki w działającej aplikacji.

## B. Fakty w kodzie, od których zaczynamy

- MessagesService.kt: sendPendingMessages sprawdza Working Hours przed dalszymi operacjami, a priority >= PRIORITY_EXPEDITED (100) omija to sprawdzenie.
- MessagesService.kt: sendMessage (~382) wywołuje sendSMS/sendMMS. Nie jest to wystarczająca fizyczna granica: między nim a SmsManager są dodatkowe operacje i opóźnienia.
- MessagesService.kt: sendSMS (~495) przygotowuje SmsManager i w pętli po odbiorcach wywołuje sendFn; wewnątrz są sendMultipartTextMessage (~520), sendTextMessage (~530) i sendDataMessage (~560).
- MessagesService.kt: sendMMS (~417) kieruje do MmsSender.send (~461).
- Obecny kod przechodzi przez withContext(NonCancellable); to nie może omijać lokalnej polityki.
- Istniejące Working Hours = pojedyncze dzienne okno, domyślna strefa telefonu, priorytet jest wyjątkiem.
- Android Local Server przechowuje wiadomości w Room, obsługuje caller-provided message ID i zwraca konflikt 409 dla duplikatu.
- Odrębna gałąź feat/local-server-resilience ma poprawki cyklu życia usługi oraz szkic CI; nie była jeszcze potwierdzona kompilacją ani testem na docelowym telefonie.
- Java 17 jest w WSL; Windows SDK na Patryk ma platformy android-34/35/36. Projekt kompiluje względem SDK 33. Potrzeba sprawdzić/uzupełnić narzędzia zamiast zakładać gotową kompilację.

Aktualny stan planu i status środowiska trzeba ponownie potwierdzić przed rozpoczęciem każdego PR.

## C. Model i granice odpowiedzialności

**System biznesowy (CRD Finance / Excel / n8n):** wyznacza adresata, kwotę, treść i zgodę na komunikację. Nie zna statusów Androida.

**Logiczny dispatcher:** przyjmuje zlecenia, nadaje trwałe ID, prowadzi zatwierdzenia kampanii, ogranicza liczbę zleceń na telefonie, deduplikuje żądania i rekoncyliuje statusy. Początkowo adapter istniejącej SMSApp; własny serwis z bazą tylko jeśli uzasadnią go wymagania wielu klientów i niezależnej kolejki.

**Android Gateway:** jest właścicielem lokalnej polityki rozpoczynania fizycznych prób, stanu kolejki urządzenia, przetrwałej rejestracji prób i callbacków systemowych.

**Operator GSM:** dostarczenie wiadomości poza kontrolą aplikacji.

Przykładowy lifecycle faktów (nie należy scalać ich w jedno pole Sent):

1. RequestAccepted — żądanie przyjęte przez system logiczny.
2. QueuedOnDevice — telefon zapisał żądanie trwale i zna jego ID.
3. AttemptReserved — telefon trwale zapisał zamiar fizycznej próby; od tego punktu nie wolno ślepo ponawiać.
4. AttemptInvoked — wywołanie SmsManager rozpoczęte; zapis i faktyczna próba mogą się rozjechać po awarii.
5. AndroidSent — callback sentIntent wskazuje sukces nadania systemowego.
6. AndroidSendFailed — callback sentIntent wskazuje błąd; możliwość ponowienia zależy od konkretnej klasy błędu.
7. Delivered / DeliveryUnknown — raport doręczenia jest niezależny i opcjonalny.
8. AttemptUnknown — wynik niejednoznaczny; automatyczne ponowienie zabronione bez weryfikacji.

Uwaga: AttemptReserved może bezpiecznie klasyfikować nadmiernie ostrożnie (crash przed samym SmsManager). To zamierzony koszt strategii preferującej brak duplikatu nad automatyczne ponowienie.

## D. Invariants (niezależne od implementacji)

- INV-01: żadna ścieżka wykonywania nie rozpoczyna nowej operacji SMS/MMS poza dozwolonym oknem; wszystkie priorytety podlegają tej samej polityce.
- INV-02: po zmianie polityki jej wersja obowiązuje przy następnej nierozpoczętej próbie. Kontrola i wywołanie SmsManager muszą być zsynchronizowane ze zmianą reguł (brak TOCTOU).
- INV-03: przy polityce brakującej, niepoprawnej, niezweryfikowanej lub zegarze uznanym za niewiarygodny — fail closed, wiadomości pozostają w kolejce.
- INV-04: blokada godzin nie zmienia wiadomości Pending na Failed, nie usuwa ich i nie prowadzi do busy loop.
- INV-05: idempotency key/logical message ID nie zmienia się podczas retry HTTP; konflikt 409 oznacza read/reconcile, nie wysyłkę z nowym ID.
- INV-06: po możliwym wykonaniu SmsManager brak odpowiedzi oznacza UNKNOWN, nie READY do automatycznej ponownej emisji.
- INV-07: każda próba ma trwały identyfikator, znaczniki czasu i wersję decyzji polityki; callbacki aktualizują tę samą próbę.
- INV-08: brak routingu wiadomości tej samej kampanii jednocześnie przez SMS8 i nowy gateway.
- INV-09: numery, treści, klucze i tokeny nie trafiają do publicznego repo, logów CI ani niezaszyfrowanej telemetrii.
- INV-10: testy jednostkowe i CI nie wykonują żadnego prawdziwego SMS.

## E. Implementacja w małych PR-ach

Zależności: E0 -> E1 -> E2 -> E3 -> E4 -> E6. E5 (odporność) można równolegle przygotować, ale scalanie wymaga przejścia testów E2. E7 (pilot) dopiero po E3–E6.

### E0 — build baseline i bezpieczny toolchain

**Zakres:** na oddzielnej gałęzi weryfikacja Gradle wrapper, Java 17, SDK 33, repozytoriów zależności i CI; zbudowanie debug APK/testów bez sekretów produkcyjnych. Ocena gałęzi resilience i skryptu CI bez przedwczesnego merge.

**Testy/dowody:** gradle testDebugUnitTest i assembleDebug w powtarzalnym środowisku; artifact SHA-256; brak podpisywania produkcyjnym kluczem; brak instalacji na telefonie i brak wysyłki.

**STOP:** jeśli projekt nie buduje się deterministycznie — naprawiamy toolchain przed implementacją modelu.

### E1 — czysty evaluator SendingPolicy (Issue #2, PR-A)

**Dane:** SendingPolicy(version, timezone, weeklyAllowedWindows, blackoutDates, blackoutIntervals, globalPaused, validity/freshness).

**Semantyka:** pause > blackout > weekly window > deny. Okna są półotwarte [start,end). Jawna strefa Europe/Warsaw. Wiele przedziałów na dzień, przejście przez północ, dni bez okien. Wyznaczanie nextAllowedAt musi być deterministyczne. Zmiana czasu letniego/zimowego i powtórzona godzina mają testy.

**Kod:** osobny moduł policy z Clock i portem odczytu. Rozstrzygnąć kompatybilność java.time przy minSdk21: desugaring lub kompatybilny evaluator, decyzja po uruchomieniu build baseline.

**Testy:** tabela dat i godzin; sobota/niedziela; blackout 11.11; pauza; niepoprawna strefa; DST 2026-03-29 i 2026-10-25; następne otwarcie, zmiana reguł; błędy konfiguracji i brak domyślnego allow.

**DoD:** evaluator działa jako pure unit tests, bez modyfikowania SmsManager lub systemu nadawczego.

### E2 — atomowa bramka przy każdej próbie fizycznej (Issue #2, PR-B)

**Zakres:** egzekwowanie polityki dla:
- SMS text oraz multipart (jedno wywołanie SmsManager na logicznego odbiorcę),
- SMS data,
- MMS,
- wielu odbiorców tego samego requestu,
- worker, FCM, scheduled i priority >=100.

**Istotne:** check tylko na początku sendMessage() jest niedostateczny. Recheck musi wystąpić bezpośrednio przed każdą operacją sendFn w pętli odbiorców oraz przed fizyczną próbą MMS. Zmiana polityki i rozpoczęcie próby muszą mieć jedno źródło prawdy i odpowiedni lock/wersjonowanie (bez trzymania blokady przez asynchroniczne callbacki).

**Po odmowie:** Pending zostaje Pending, zapisujemy reason, policyVersion i nextAllowedAt; worker planuje wznowienie, nie uruchamia gorącej pętli. Priority oraz NonCancellable nie mogą ominąć gate.

**Testy:** mock/fake SmsManager, licznik wywołań; kombinacje per recipient, priority 0/100/127, czas 20:59:59 / 21:00:00, zmiana polityki między dwoma odbiorcami, race pause vs send, odroczony worker budzący się w nocy, Data i MMS. Audyt wszystkich call sites w repo.

**DoD:** zero wywołań fake modem w oknie blocked; nie ma obejścia ani utraty kolejki. Build i testy zielone. Na tym etapie bez realnej kampanii.

### E3 — wersjonowana polityka lokalna, pauza i bezpieczny interfejs (Issue #3)

**Zakres:** trwały model polityki na telefonie (Room/konfig), atomowe apply, walidacja przed ACK, wersja i znacznik ostatniej aktualizacji, endpointy GET policy i PUT policy oraz pauza/wznowienie z autoryzacją. Zakazać cichego allow przy błędzie importu. Restart zachowuje ostatnią potwierdzoną politykę; nieznana wersja/niepoprawny stan blokuje nadawanie.

**Testy:** aktualizacja polityki podczas pracy, cofnięcie konfliktującej wersji, restart offline, token invalid/expired, fail closed, stara strefa, zły zegar, przywrócenie ustawień.

**DoD:** po zmianie i ponownym uruchomieniu urządzenia można odczytać obowiązującą wersję i dowód jej zastosowania. Endpoint HTTP dostępny wyłącznie po uwierzytelnieniu, w zaufanej sieci lub VPN; bez publicznego wystawienia portu.

### E4 — durable attempt ledger i retry/recovery (Issue #3)

**Zakres:** trwałe rozróżnienie enqueue, attempt reservation, wywołania SmsManager i callbacków. Stable ID; oddzielne próby od logicznej wiadomości; powiązanie z sentIntent/deliveryIntent. Jawna kwarantanna Unknown. Jedna aktywna próba na (message ID, recipient) i spójność przy współbieżnych workerach.

**Testy:** crash przed zapisem próby, po zapisie/przed send, po send/przed callback, callback podwójny i spóźniony, timeout HTTP, POST ponowiony z tym samym ID, 409, brak sygnału i błędy Androida. Żaden scenariusz nie może automatycznie ponowić fizycznie niepewnej próby.

**DoD:** w testach historii nie pojawiają się dwa fizyczne send invocation dla tego samego logicznego odbiorcy bez nowej, jawnej decyzji recovery. Dokumentacja uczciwie opisuje brak exactly-once na sieci.

### E5 — odporność Local Server (Issue #5, osobny PR)

**Zakres:** przejrzeć istniejącą gałąź feat/local-server-resilience: foreground service, boot receiver, START_STICKY, uruchomienie Ktor, diagnostyka /health. Zintegrować dopiero po testach Android CI. Rozróżnić zdrowie serwera HTTP od gotowości SMS modemu i zgodności polityki.

**Testy:** 24-godzinny monitoring /health z ekranem wyłączonym, usunięcie z recent apps, reboot, chwilowy brak Wi-Fi, Doze, dobrowolny Stop/Force-stop. Bez agresywnego watchdog restartującego wyłączoną aplikację.

**DoD:** restart nie wyłącza polityki i nie rozpoczyna nocnego nadawania; proces może wrócić później, ale bez utraty bezpieczeństwa.

### E6 — minimalny dispatcher i integracja (Issue #4)

**Pierwszy cut:** adapter istniejącej SMSApp do Android Local Server, bez nowego UI i bez nowego subsystemu kampanii; jednocześnie tylko jeden transport per campaign. Zasób wiadomości na telefonie jest mały (start: 1 nierozstrzygnięte zlecenie), serwer trzyma resztę.

**Kolejny cut po pilocie:** jeśli Excel, n8n i CRD muszą działać niezależnie i stale, jeden self-hosted dispatcher + trwała baza Postgres, jeden worker. Publiczne API z kluczem idempotencji i kontraktami odczytu statusów, bez Kafka/RabbitMQ i bez mikroserwisów.

**Testy:** 1100 syntetycznych SMS, przerwanie procesu, telefon offline, 409 po timeout, stop/resume, restart serwera, kontrola ponowień, awaria w nocy. Porównanie liczby accepted, queued, attempted, sent, delivered, unknown.

**DoD:** każdy message ID w testowej kampanii ma jedno miejsce odpowiadające za przyjęcie logiczne i możliwą rekoncyliację, a telefon ma jedno źródło prawdy o próbach fizycznych.

### E7 — odbiór operacyjny, pilot i przełączenie

**Przygotowanie:** sprawdzić dedykowany model telefonu (obecny SMS8: SM-A336B; wcześniejszy plan wydzielonego testowego Samsung A35), SIM, kontrakt operatora, zasilanie, politykę danych, podpis APK, aktualizacje i powrót do starszej wersji.

**Laboratorium:** debug APK, tylko kontrolowane numery, celowe timeouty i rozłączenia. Dokładny dziennik wywołań/faktycznych callbacków; test realnej nocy i powrotu o 02:00 oraz po 08:00. Testy po zmianie czasu systemowego. Wysyłki testowe wymagają odrębnego jawnego zatwierdzenia.

**Pilot:** mała zatwierdzona partia, kontrolowany przedział godzin; porównanie stanów urządzenia i logów. Nigdy nie importować automatycznie niepewnych historii SMS8 jako gotowych do nowego nadania.

**Cutover:** status aktywnego transportu na poziomie kampanii; stare kolejki SMS8 zweryfikowane i zatrzymane/rozliczone; przy awarii rollback kieruje wyłącznie nowe zlecenia do starego transportu, już rozpoczęte pozostają do osobnej rekoncyliacji.

**DoD:** udokumentowany test kontrolny z odbiorem operatora; GO tylko przy braku nocnych prób i ślepych duplikatów. W przeciwnym razie NO-GO.

## F. Wymagana macierz testowa (przekrojowa)

| Scenariusz | Oczekiwany wynik | Główne etapy |
|---|---|---|
| 07:59:59 / 08:00:00 / 20:59:59 / 21:00:00 Europe/Warsaw | Dokładna granica okna; brak nowej próby od 21:00 | E1 E2 E7 |
| Wysoki priorytet 100/127 | Brak obejścia godziny i pauzy | E2 |
| SMS do dwóch odbiorców; polityka zmienia się pomiędzy | Pierwszy już rozpoczęty może się zakończyć, drugi pozostaje Pending | E2 E3 |
| Długie SMS multipart, data SMS i MMS | Sprawdzenie każdej fizycznej ścieżki | E2 |
| Pauza o 20:59:59 i równoczesny worker | Brak TOCTOU; rozstrzygnięcie w sposób deterministyczny | E2 E3 |
| Awaria po zapisaniu attempt, przed SmsManager | UNKNOWN lub jawne bezpieczne rozstrzygnięcie, nie blind retry | E4 |
| Awaria po SmsManager, bez sentIntent | UNKNOWN i kwarantanna | E4 |
| Callback po 21:00 | Można zarejestrować callback wcześniejszej próby; to nie jest nowe nadanie | E2 E4 |
| Telefon offline o 20:30, online o 02:00 | Brak nowych fizycznych prób do dozwolonego okna | E2 E5 E7 |
| Restart Androida i zmiana czasu/DST | Polityka trwała, fail-closed przy błędzie czasu | E1 E3 E5 |
| POST timeout, ponowienie identycznego ID | 409/read/reconcile, bez drugiej emisji | E4 E6 |
| 1100 syntetycznych zleceń | Kompletność stanów, brak logicznych duplikatów | E6 |
| Nocne callbacki z sieci | Poprawnie księgowane bez rozpoczęcia nowego send | E4 E7 |

## G. Ryzyka, które mogą obalić model

1. **Ścieżka fizyczna nie jest jedyna:** nieznane wywołanie SmsManager, bezpośrednie MMS lub inna usługa. Mitigacja: globalny audyt call sites i test statyczny.
2. **Zmiana polityki w trakcie wieloodbiorcowego send:** kontrola tylko na poziomie requestu zawodzi. Mitigacja: per recipient + serializacja decyzji i krótkiego invoke.
3. **Niepewny zegar urządzenia:** lokalny czas może być zmieniony lub niezsynchronizowany. Mitigacja: jawna polityka dla skoku zegara i świeżości źródła czasu, fail-closed; zaznaczyć ograniczenie fizycznych gwarancji.
4. **Android/OEM zabija worker:** wysyłka może się opóźnić po wznowieniu. Akceptujemy opóźnienie, nie nocne obejście; test Samsung.
5. **Callback nie przychodzi:** Unknown, nie automatyczne retry.
6. **Dwie bazy stają się źródłem prawdy:** dispatcher trzyma zlecenie, Android próbę; stabilny ID i API reconciliation, nie kopiowanie jednego stanu Sent.
7. **Publiczny fork zawiera dane/sekrety:** nie commitować prawdziwych wiadomości, numerów ani kluczy; w testach fixture syntetyczne.
8. **Upstream aktualizuje kod:** utrzymywać mały diff, osobne PR, okresowy rebase i ponowny audyt ścieżek modemu.
9. **Operator GSM narzuca limity:** sprawdzić warunki SIM oraz dostarczalność podczas pilota, nie na podstawie akceptacji REST.

## H. Decyzje i pytania przed pilotem

- DECISION D1: Android egzekwuje twardą politykę, dispatcher jest tylko dodatkową warstwą ochronną.
- DECISION D2: priorytet administracyjny nie może omijać blokady.
- DECISION D3: po możliwej próbie fizycznej UNKNOWN nie jest automatycznie wysyłane ponownie.
- DECISION D4: jeden transport per kampania i brak masowego prefetch na urządzeniu.
- DECISION D5: zaczynamy od E0–E2; backend dopiero po sukcesie twardego kalendarza.
- OPEN Q1: telefon docelowy SM-A336B czy osobny Samsung A35?
- OPEN Q2: produkcyjna polityka (domyślnie 08:00–21:00 Europe/Warsaw), wykluczenia dni i wcześniejszy cutoff dla nowych przyjęć.
- OPEN Q3: gdzie działa dispatcher (istniejący Windows/WSL vs zawsze włączony serwer)?
- OPEN Q4: jak mierzyć i reagować na rozbieżność czasu telefonu względem zaufanego źródła?
- OPEN Q5: czy i jak system ma obsługiwać MMS, skoro CRD obecnie potrzebuje przede wszystkim tekstowych SMS?

Brak rozstrzygnięcia Q1–Q5 nie blokuje E0 ani E1. Q1, Q2 i Q4 wymagają decyzji przed E7.

## I. Jak pracować — reguła checkpointu

Każdy etap kończy się jednym niewielkim PR z:
- opisem poprawianego invariantu;
- dowodami z kodu i testami dla happy path oraz failure modes;
- wynikiem kompilacji w CI i identyfikatorem testowanego commita;
- wykazem nierozstrzygniętych hipotez i ryzyk;
- jawnie wskazanym brakiem SMS na produkcję;
- aktualizacją docs/custom/CHECKPOINT.md dopiero po pozytywnej weryfikacji.

Żadnego automatycznego instalowania APK, uruchamiania wysyłki, przepinania produkcyjnych numerów ani migracji SMS8 tylko dlatego, że PR jest zielony.

**Pierwszy krok realizacyjny po zatwierdzeniu planu:** E0 (zielony build na odizolowanym CI) i E1 (testy evaluator), potem E2 (hard gate). Plan nie jest dowodem, że te zadania są wykonane.
