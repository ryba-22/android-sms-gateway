# E6 — trwała kolejka / pilot prototypowy (bez SMS)

Zakres: Issue #4. **To prototyp kontraktu i testów; nie jest to gotowy transport produkcyjny.**

Oddzielny Draft PR oparty o `master`; zależność funkcjonalna od Android PR #7 (szczególnie GET /policy, GET /messages/{id}/attempts, trwały rejestr prób). **Nic nie instaluje i nie wysyła SMS-ów.** Nie zmienia istniejącej SMSApp ani aktywnych kampanii SMS8.

## Faktyczny model

Dane biznesowe (CRD Finance, Excel i SMSApp) wyznaczają **adresata, treść, powód, kampanię i business_key**. Dispatcher przechowuje *zlecenie wysyłki* i postęp logiczny. Telefon Android przechowuje *próbę fizyczną* i jej wynik. To nie są dwa równoważne pola statusu.

Trwała baza SQLite (lokalna, uprawnienia 0600) zawiera:
- campaign: id, approved_by, approved_at
- dispatch_message: stabilne message_id, business_key, telefon, treść, hash, stan
- dispatch_attempt: rezerwacja próby przekazania do telefonu

Stany: DRAFT -> READY -> SUBMITTING -> DEVICE_ACCEPTED -> SENT/DELIVERED (lub FAILED). Timeout/niejasna odpowiedź: SUBMISSION_UNKNOWN. Niejednoznaczny status Androida: ATTEMPT_UNKNOWN. Żadnego automatycznego powrotu UNKNOWN do READY.

**Cztery własności:**
1. (campaign_id,business_key) to klucz idempotencji. Próba podmiany telefonu/treści pod tym kluczem to konflikt, nie nowa wiadomość.
2. Wysyłanie wymaga jawnego zatwierdzenia kampanii z liczbą wiadomości oraz potwierdzenia polityki przez **telefon**. Bez tego dispatcher nie rezerwuje żadnej wiadomości.
3. Przed każdym HTTP POST trwała transakcja SQLite zapisuje stan SUBMITTING. Przy timeout/przerwaniu procesu identyczny message_id nie jest ponawiany automatycznie. HTTP 409 oznacza **GET i rekoncyliację**, nie alternatywne ID.
4. Globalnie **jedna nierozstrzygnięta wiadomość** na telefon: nie nadmuchujemy lokalnej kolejki setkami SMS-ów.

Założenie operacyjne: odbiór wiadomości nie jest gwarantowany natychmiast po zatwierdzeniu; z powodu powyższych blokad kolejka może stanąć, aż operator wyjaśni UNKNOWN. Ten koszt jest zamierzony.

## Weryfikacja bez sieci

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p "test_*.py" -v
PYTHONDONTWRITEBYTECODE=1 python3 -m dispatch.simulate --count 1100
```

Symulator nie importuje Androida, nie korzysta z GSM i nie otwiera połączeń HTTP. Dane i numery są syntetyczne. Zwraca podsumowanie: 1100 przygotowanych, 1100 symulowanych przekazań, 1100 zrekoncyliowanych, bez powtórzeń identyfikatorów. Testy obejmują brak odpowiedzi po akceptacji SMS, crash po lokalnym claim, zablokowane godziny, równoległych workerów, konflikt treści i ochronę zapisu bazy.

## Przyszły kontrakt HTTP — nie uruchamiać z prawdziwym telefonem bez odbioru

`dispatch/gateway_client.py` implementuje po stronie komputera:
- GET /policy — configured=true, allowedNow=true, paused=false, version>0, Europe/Warsaw
- POST /messages — jedno ID, jeden odbiorca, jeden textMessage, priority=0
- GET /messages/{id} — status fizycznego nadania i stan odbiorcy
- GET /messages/{id}/attempts — historia fizycznych prób (endpoint z PR #7)

Klient jest biblioteką, **nie ma komendy do rzeczywistej wysyłki**. Każde wywołanie `Dispatcher.dispatch_one` wymaga jawnego `explicitly_enabled=True`; fabryczny tryb odmawia przekazania. HTTPS domyślne, HTTP wyłącznie po świadomym dopuszczeniu prywatnego adresu IP. Adres, token, numer SIM i hasło nie są w repozytorium.

## Blokady produkcyjne

- SQLite przechowuje treść i numer jako zwykły tekst. Prawdziwe dane osobowe wymagają decyzji o szyfrowaniu, retencji i kontroli dostępu lub przeniesienia do zabezpieczonego PostgreSQL. **Nie importować danych rodziców do prototypu.**
- Odbiór na Samsungu i testy FGS/Doze/RTC/21:00/02:00 oraz sentIntent/deliveryIntent nadal niewykonane.
- Brak jawnego operatorowego procesu rozwiązania UNKNOWN i kontraktu uzgadniania z SMS8. Nigdy nie wznowić „zaległych” SMS bez zweryfikowania historii.
- E6 nie podłączono do produkcyjnej SMSApp. Jej lokalne zmiany w katalogu `sms_powiadomienia/smsapp` pozostają nietknięte.
- Testy nie gwarantują dokładnie jednokrotnego doręczenia przez operatora GSM.
- PR #7 i niniejszy PR muszą przejść niezależny przegląd zmian przed połączeniem. Prototyp nie może zastąpić backendu bez decyzji o wymaganiach wielodostępowych, HA i kosztach awarii.

**GO: NO dla prawdziwych SMS.**
