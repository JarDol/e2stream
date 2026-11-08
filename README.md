# E2Stream

Odtwarzacz strumieni z dekodera Enigma2 (OpenPLi / OpenWebif) dla Android TV.

* ExoPlayer (Media3) z **tunelowaniem wideo** – ta sama ścieżka co DreamTV z ExoPlayerem + tunelowanie
  (sprzętowy deinterlacer telewizora, płynne paski informacyjne).
* **Watchdog** – gdy obraz się zawiesi (licznik klatek dekodera albo zegar odtwarzania stoi
  dłużej niż ustawiony limit, zbyt długie buforowanie, błąd lub zerwane połączenie),
  strumień jest automatycznie otwierany ponownie. To samo, co ręczne „przełącz i wróć”, tylko w ok. 1–2 s.
* Bukiety i kanały pobierane z OpenWebif (`/api/getservices`), EPG „teraz” (`/api/epgnow`).
* Strumień: `http://DEKODER:8001/<referencja>` (opcjonalnie z logowaniem Basic).
* Wyszukiwanie dekoderów w sieci lokalnej (ustawienia → „Szukaj dekoderów w sieci”).
* Wybór ścieżki dźwięku zapamiętywany osobno dla każdego kanału; preferowany język i opcjonalnie AC3.
* Odświeżanie ekranu: bez zmian / zawsze 50 Hz / automatycznie wg treści.
* Analizator strumienia TS: błędy CC, TEI, pakiety kodowane, utrata synchronizacji, skoki PCR,
  zmiany PMT, przerwy w napływie danych — w statystykach i w logu.
* Przy zawieszeniu zapis ostatnich sekund strumienia (`stall-*.ts`, bufor 16 MB) do analizy np. TSDuck.
* Miejsce zapisu: pamięć urządzenia lub pendrive/karta (katalog `Android/data/pl.jardol.e2stream/files/`).
* EPG pobierane codziennie wg harmonogramu (i na żądanie) z zapisem na urządzeniu; ikony kanałów z dekodera (OpenWebif /picon/ lub FTP).
* EPG kanału, multi-EPG bukietu i wyszukiwanie w EPG; z audycji: przełącz na kanał lub nagraj na dekoderze.
* Wybór widocznych bukietów, PIN do ustawień, aktualizacja aplikacji z GitHub (menu → Aktualizacja).
* Log diagnostyczny: lokalnie w `Android/data/pl.jardol.e2stream/files/e2stream.log`
  i co 2 minuty przez FTP na dekoder (domyślnie `/media/hdd/e2stream/e2stream-<model>.log`).

## Synchronizacja z dekoderem

Na dekoderze w `<katalog FTP>/sync/` (domyślnie `/media/hdd/e2stream/sync/`):

* `common.json` — wartości domyślne dla nowych odbiorników i wspólne PMT kanałów,
* `device-<id>.json` — ustawienia i wybrane ścieżki dźwięku każdego odbiornika (z nazwą i modelem).

Nowy odbiornik przy pierwszym uruchomieniu bierze swój profil (reinstalacja) albo wartości domyślne.
Zmiany zapisują się w pliku odbiornika; klucz nieobecny w części wspólnej jest tam dopisywany jako domyślny.
Synchronizacja działa w tle: przy starcie, kilka sekund po zmianie, co 2 minuty i przy wyjściu.
Ustawienia → Synchronizacja: nazwa odbiornika, synchronizuj teraz, przenieś ustawienia z innego odbiornika.

## Pilot

| Klawisz | Działanie (domyślnie — można zmienić: Ustawienia → Mapowanie klawiszy) |
|---|---|
| OK | lista kanałów (◀ ▶ zmiana bukietu; długie OK na kanale — opis bieżącej audycji) |
| przytrzymanie OK | menu: EPG wydarzenia, EPG kanału, Multi EPG, Szukaj w EPG, Ścieżka dźwięku, Ustawienia |
| ▲ / ▼, CH+ / CH− | następny / poprzedni kanał |
| ◀ | EPG kanału |
| ▶ | Multi EPG bukietu (w nim ◀ ▶ zmiana czasu o 30 min) |
| INFO | EPG wydarzenia — opis bieżącej audycji |
| GUIDE / EPG | Multi EPG |
| SEARCH | szukaj w EPG |
| MENU | menu |
| cyfry | wybór numeru kanału (OK zatwierdza od razu) |
| LAST / ⏮ | poprzedni kanał |
| WSTECZ ×2 | wyjście |

## Ustawienia

* adres dekodera, port OpenWebif (80), port streamingu (8001), login/hasło,
* tunelowanie (domyślnie włączone),
* watchdog i próg zawieszenia (domyślnie 3 s),
* bufor startowy (domyślnie 700 ms),
* odświeżanie ekranu (bez zmian / 50 Hz / automatycznie),
* preferowany język dźwięku i AC3,
* log przez FTP: użytkownik (root), hasło, katalog na dekoderze.

## Budowanie

Workflow `.github/workflows/build.yml` buduje APK przy każdym pushu na `main`.
Jeśli wersja (`versionName`) jest nowa, automatycznie tworzy Release `vX.Y.Z`
z plikiem `e2stream-X.Y.Z.apk`.

Lokalnie: Android Studio lub `./gradlew assembleRelease` (JDK 17, Android SDK 35).

APK jest podpisywany stałym kluczem `keystore/e2stream.jks` – kolejne wersje instalują się
jako aktualizacja bez odinstalowywania. To klucz do prywatnego użytku; przy publikacji
w sklepie trzeba go zmienić.

## Instalacja na TV

* `adb connect IP_TV && adb install -r e2stream-X.Y.Z.apk`, albo
* aplikacja *Downloader* → link do pliku APK z GitHub Release.

## Licencja

GPL-2.0-or-later. Korzysta z AndroidX Media3 (Apache-2.0).
