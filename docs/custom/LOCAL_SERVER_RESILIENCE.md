# Samsung A35 — odporność Local Server na zatrzymanie procesu

Zakres: Issue #5, niezależny od twardego kalendarza (Issue #2). Nie zmieniamy SmsManager, API ani semantyki kolejki.

## Fakty i zmiany

- Upstream posiada foreground WebService, PARTIAL_WAKE_LOCK, WiFiLock, /health i BootReceiver.
- Foreground notification uruchamiana jest teraz przed startem Ktor/Netty, aby nie opóźniać promocji usługi.
- `onStartCommand()` zwraca `START_STICKY`: po zabiciu procesu przez system Android *może* odtworzyć usługę, także z `null` intent. To nie gwarantuje natychmiastowego wznowienia.
- `ACTION_SHUTDOWN` nie jest już zdarzeniem uruchamiającym serwer.
- Błędy startu Local Server i Ping są oddzielnie raportowane.
- Komponent WebService ma jawne `android:exported="false"`. Dotyczy komponentu Android, NIE portu HTTP: sieć wymaga osobnej kontroli dostępu.

## Ograniczenia

- START_STICKY nie obchodzi świadomego Stop/Force-stop, ograniczeń OEM, odmowy uprawnień ani braku sieci.
- Po restarcie może być konieczne pierwsze odblokowanie urządzenia w celu odczytu danych przechowywanych w credential-encrypted storage.
- HTTP 200 z /health oznacza dostępność usługi/jej diagnostyki, nie gotowość modemu do dostarczenia SMS.
- Aplikacja aktualnie używa targetSdk 33; aktualizacja do 34+ wymaga audytu typów FGS i odpowiednich uprawnień.
- Stałe blokady CPU/Wi-Fi to istniejące zachowanie upstream. Podczas testu sprawdzić temperaturę i zużycie energii.
- Worker wysyłkowy nadal podlega ograniczeniom WorkManager i Android 16.

## Test akceptacyjny na Samsung A35 / One UI 7 lub 8

1. Zainstalować zweryfikowaną testową kompilację APK i włączyć Local Server oraz autostart.
2. Sprawdzić `http://PHONE_IP:PORT/health` z zaufanej sieci LAN/VPN. Nie wystawiać portu do internetu.
3. Wyłączyć ekran; monitorować /health przez 24 h co 30–60 s; zapisywać okresy niedostępności.
4. Usunąć aktywność z listy ostatnich aplikacji: serwer powinien pozostać dostępny.
5. Uruchomić telefon ponownie, odblokować go, jeśli to konieczne, i zmierzyć powrót /health.
6. Symulować zabicie procesu mechanizmem developerskim bez użycia `am force-stop`, które z założenia blokuje automatyczne wznowienie.
7. Nacisnąć Stop w bramce: serwer musi pozostać wyłączony; START_STICKY nie ma go samoczynnie uruchomić.
8. Przy błędach sprawdzić logcat i odróżnić problem usługi FGS, uruchomienia Netty i uprawnień SMS.

## Wykonana weryfikacja

XML manifestu poprawny; sprawdzone filtry boot/shutdown, nieeksportowany WebService, kolejność startu FGS, START_STICKY, osłona zwalniania blokad; `git diff --check` bez błędów.

Brak Android SDK na maszynie developerskiej: kompilacja i testy instrumentacyjne nie zostały jeszcze uruchomione. Nie instalowano APK i nie wysyłano SMS-ów.

Dokumentacja: https://developer.android.com/reference/android/app/Service oraz https://developer.android.com/develop/background-work/services/fgs/changes
