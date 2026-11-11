# Velo — traduzione dello schermo in italiano (Android)

Velo legge di continuo ciò che appare sullo schermo, riconosce il testo straniero e lo
copre con la traduzione in italiano, sopra qualsiasi app (browser, social, giochi, app di
shopping…). Lettura del testo e traduzione avvengono **sul telefono**: nessuna schermata
viene inviata a server esterni.

Richiede **Android 8.0 o successivo**.

---

## 1. Ottenere l'APK

Non serve installare nulla sul computer: l'APK viene compilato gratis da GitHub.

1. Crea un repository su GitHub (meglio **privato**, vedi nota sulla firma) e carica tutto il
   contenuto di questa cartella, inclusa la cartella nascosta `.github`.
2. Al primo push sul ramo `main` parte da solo il workflow **Build APK** (scheda *Actions*,
   circa 5–8 minuti).
3. A fine build l'APK è nella pagina *Releases*, scaricabile direttamente dal telefono:
   `https://github.com/<utente>/<repo>/releases/latest/download/velo.apk`

In alternativa: apri la cartella con **Android Studio** (Koala o successivo) e premi *Run*,
oppure da terminale `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Installare sul telefono

1. Apri `velo.apk` dal telefono; Android chiederà di consentire l'installazione da quella
   fonte (browser o file manager): consenti.
2. Apri Velo, concedi **Mostra sopra le altre app** e le **notifiche**.
3. Premi **Avvia traduzione** e, quando Android chiede cosa condividere, scegli
   **Schermo intero**.

## 3. Uso

- Apri qualsiasi app: il testo straniero viene coperto dalla traduzione.
- Quando scorri, le traduzioni spariscono e ricompaiono appena il contenuto si ferma.
- **Bolla flottante**: tocco = pausa/riprendi · pressione lunga = apre Velo · trascinamento =
  sposta (si aggancia al bordo).
- Dalla notifica: *Pausa* e *Ferma*.
- La prima volta che incontra una lingua, Velo scarica il relativo pacchetto (~30 MB, solo con
  Wi‑Fi salvo diversa impostazione). Poi funziona anche offline.

## 4. Lingue

Lingue di origine leggibili: tutte quelle in **alfabeto latino** (inglese, francese, spagnolo,
tedesco, portoghese, olandese, polacco, rumeno, turco, vietnamita, ecc.), **cinese**,
**giapponese**, **coreano** e **hindi/marathi** (devanagari).

Con il rilevamento automatico Velo legge un alfabeto alla volta (impostabile nell'app) e
riconosce la lingua di ogni blocco di testo; il testo già in italiano non viene toccato.

**Non supportate** dal riconoscimento del testo sul dispositivo: cirillico (russo, ucraino…),
arabo, greco, ebraico, thai e altri alfabeti indiani.

## 5. Limiti noti (di Android, non aggirabili da un'app)

- Le app che proteggono lo schermo (banche, Netflix, Disney+, alcune chat in modalità
  privata) appaiono nere nella cattura: lì Velo non può tradurre.
- Da Android 12 una finestra sovrapposta che lascia passare i tocchi non può essere opaca oltre
  l'80%: il testo originale resta leggermente visibile sotto la traduzione. In cambio puoi
  continuare a toccare e scorrere l'app sotto normalmente.
- Su Android 14+ il consenso alla condivisione dello schermo va dato a ogni avvio.
- Alcuni produttori (Xiaomi/MIUI, Huawei) richiedono anche di consentire «finestre pop-up in
  background» e di escludere Velo dall'ottimizzazione della batteria.
- La traduzione offline di ML Kit è buona per capire il senso, ma meno raffinata di DeepL o di
  Google Traduttore online.

## 6. Come funziona

```
MediaProjection ──► ScreenCapturer ──► FrameSignature (impronta 48×96)
                                            │ lo schermo è cambiato?
                                            ▼
                     svuota il livello ◄── sì ──► aspetta che sia fermo
                                                      │
                     ML Kit Text Recognition (OCR) ◄──┘
                                │
                     ML Kit Language ID + Translate (→ italiano, offline)
                                │
                     ColorSampler (colori di sfondo e testo originali)
                                │
                     TranslationLayerView (finestra sovrapposta, non tocca i tocchi)
```

Punti chiave del progetto:

- **Niente auto-traduzione**: l'OCR lavora solo su fotogrammi catturati quando il livello delle
  traduzioni è vuoto, e l'area della bolla è esclusa.
- **Batteria**: se lo schermo non cambia, Android non invia fotogrammi e Velo non rifà l'OCR.
  Le zone sempre in movimento (video, animazioni) vengono riconosciute e ignorate.
- **Risultati coerenti**: se lo schermo cambia mentre la traduzione è in corso, il risultato
  viene scartato invece di essere disegnato nel posto sbagliato.
- **Cache** delle ultime 800 traduzioni: tornando su una schermata già vista è istantanea.

Struttura del codice (`app/src/main/java/it/velo/traduttore/`):

| File | Ruolo |
|---|---|
| `service/TranslateService.kt` | Servizio in primo piano e ciclo principale |
| `capture/ScreenCapturer.kt` | Specchio dello schermo, gestione rotazione |
| `capture/FrameSignature.kt` | Rilevamento rapido dei cambiamenti |
| `ocr/TextExtractor.kt` | Riconoscimento del testo e raggruppamento in blocchi |
| `translate/TranslationEngine.kt` | Lingua, download pacchetti, traduzione, cache |
| `overlay/*` | Livello traduzioni, bolla flottante, campionamento colori |
| `MainActivity.kt` | Permessi e impostazioni |

## Nota sulla firma

L'APK è firmato con la chiave inclusa (`app/velo-debug.keystore`), così ogni nuova build si
installa sopra la precedente senza perdere le impostazioni. È una chiave di sviluppo: va bene
per uso personale; per distribuire l'app (es. Play Store) va sostituita con una chiave privata
custodita fuori dal repository.
