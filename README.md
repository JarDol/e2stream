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
* Log diagnostyczny: lokalnie w `Android/data/pl.jardol.e2stream/files/e2stream.log`
  i co 2 minuty przez FTP na dekoder (domyślnie `/tmp/e2stream-<model>.log`).

## Pilot

| Klawisz | Działanie |
|---|---|
| OK | lista kanałów |
| ◀ / ▶ na liście | poprzedni / następny bukiet |
| ▲ / CH+ | następny kanał |
| ▼ / CH− | poprzedni kanał |
| ◀ | lista kanałów |
| ▶ / INFO | statystyki (rozdzielczość, kodek, klatki, ekran, liczba wznowień i ostatni powód) |
| cyfry | wybór numeru kanału (OK zatwierdza od razu) |
| przytrzymanie OK / MENU | menu: ścieżka dźwięku, statystyki, odświeżanie ekranu, wyślij log, ustawienia |
| AUDIO | wybór ścieżki dźwięku |
| LAST / ⏮ | poprzedni kanał (przełączanie dwóch ostatnich) |
| WSTECZ ×2 | wyjście |

## Ustawienia

* adres dekodera, port OpenWebif (80), port streamingu (8001), login/hasło,
* tunelowanie (domyślnie włączone),
* watchdog i próg zawieszenia (domyślnie 3 s),
* bufor startowy (domyślnie 1500 ms),
* odświeżanie ekranu (bez zmian / 50 Hz / automatycznie),
* preferowany język dźwięku i AC3,
* log przez FTP: użytkownik (root), hasło, katalog na dekoderze.

## Budowanie

Repozytorium zawiera workflow `.github/workflows/build.yml`. Po pushu na `main` APK
`e2stream-X.Y.Z.apk` pojawia się w zakładce **Actions → artefakty**. Tag `vX.Y.Z` tworzy
dodatkowo GitHub Release z APK.

Lokalnie: Android Studio lub `./gradlew assembleRelease` (JDK 17, Android SDK 35).

APK jest podpisywany stałym kluczem `keystore/e2stream.jks` – kolejne wersje instalują się
jako aktualizacja bez odinstalowywania. To klucz do prywatnego użytku; przy publikacji
w sklepie trzeba go zmienić.

## Instalacja na TV

* `adb connect IP_TV && adb install -r e2stream-X.Y.Z.apk`, albo
* aplikacja *Downloader* → link do pliku APK z GitHub Release.

## Licencja

GPL-2.0-or-later. Korzysta z AndroidX Media3 (Apache-2.0).
