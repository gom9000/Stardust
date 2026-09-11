# Stardust
Un motore di simulazione a N-corpi per la dinamica gravitazionale planetaria, dall'accrescimento
di un disco protoplanetario a sistemi orbitali semplici.

![screenshot](resources/screenshot.png)

## Dalla polvere ai pianeti
Simulare la formazione planetaria, dal singolo grano di polvere microscopico fino a un pianeta completo, non è (solo) una questione di potenza di calcolo. È un problema in cui, lungo il percorso, cambiano radicalmente sia la scala fisica in gioco sia le forze che dominano il sistema. La ricerca scientifica affronta il problema per fasi, ciascuna con i propri modelli e le proprie ipotesi semplificative.

Il percorso, dai micrometri ai migliaia di chilometri, attraversa circa tredici ordini di grandezza, la stessa distanza relativa che separa un granello di sabbia dalla Terra intera. Nessun approccio numerico riesce a coprire un intervallo così ampio, perché il numero di corpi da tracciare individualmente va  oltre ogni possibilità di calcolo, e perché le forze che contano a un estremo (elettrostatiche, di van der Waals) diventano trascurabili all'altro, sostituite dalla gravità.

Le quattro fasi in cui la ricerca suddivide il problema sono:

### 1. Coagulazione della polvere (da micrometri a centimetri/decimetri)
I grani di polvere primordiale si scontrano nel disco protoplanetario e si attaccano tra loro grazie a forze di superficie, elettrostatiche e di van der Waals, non alla gravità, che a queste masse è del tutto irrilevante. Poiché i corpi coinvolti sono in numero enorme (dell'ordine di 10¹⁸–10²⁰ per formare anche un solo oggetto di un chilometro), si ricorre a modelli statistici di distribuzione di massa.

### 2. La "barriera dei metri"
Attorno alla scala del centimetro-metro la crescita si interrompe. I corpi più grandi tendono a rimbalzare o a frammentarsi anziché fondersi, mentre il gas del disco protoplanetario esercita un attrito (aerodynamic drag) che fa perdere momento angolare, facendo precipitare i frammenti verso la stella prima che possano ingrandirsi.  
È tuttora uno dei problemi aperti: la teoria classica prevede che in questa fascia dimensionale i corpi dovrebbero cadere nella stella più velocemente di quanto riescano ad accrescersi.

### 3. Streaming instability: il salto oltre la barriera
Il superamento della barriera dei metri non avviene per accrescimento graduale, ma tramite un'instabilità aerodinamico-gravitazionale. Piccole disomogeneità locali generano un feedback con il gas che raccoglie i ciottoli (pebble) in filamenti densi, i quali collassano direttamente in planetesimi per autogravità. Questo si studia tipicamente con codici idrodinamici in shearing box.

### 4. Accrescimento gravitazionale (da planetesimi a pianeti)
I corpi hanno masse sufficienti perché la mutua gravità domini su ogni altra interazione, e il loro numero scende a livelli computazionalmente trattabili (migliaia-milioni). Le forze elettrostatiche sono fisicamente ininfluenti mentre l'attrito del gas resta rilevante per i planetesimi più piccoli, smorzandone eccentricità e inclinazioni, e favorendo così la crescita, per poi diventare via via trascurabile man mano che i corpi crescono.


## Modelli e Fenomeni Fisici

### Interazione Gravitazionale
* **Il fenomeno:** La gravità è la forza dominante della Fase 4. Regola sia l'attrazione reciproca tra i planetesimi sia il moto orbitale attorno al corpo centrale, determinando la struttura globale del disco.
* **Come funziona:** Ogni particella esercita una forza attrattiva proporzionale al prodotto delle masse e inversamente proporzionale al quadrato della distanza (legge di gravitazione universale di Newton).
* **Come lo modello:** Per gestire sistemi con migliaia di corpi senza collassare a livello computazionale ($\mathcal{O}(N^2)$), il motore adotta un approccio gerarchico basato sull'algoritmo **Barnes-Hut** ($\mathcal{O}(N \log N)$), che approssima l'attrazione dei cluster distanti tramite alberi di suddivisione spaziale (QuadTree/OctTree).

### Attrito del Gas (Aerodynamic Drag)
* **Il fenomeno:** Nel disco protoplanetario è presente un residuo di gas gassoso che orbita a una velocità leggermente inferiore rispetto ai corpi solidi (a causa del gradiente di pressione radiale). Questo crea un vento contrario che frena i planetesimi, facendone decadere l'energia orbitale.
* **Come funziona:** Il gas esercita una resistenza aerodinamica dipendente dalla densità del mezzo, dalla velocità relativa tra particella e gas, e dalle dimensioni fisiche del corpo.
* **Come lo modello:** È implementato con una transizione continua tra i regimi di **Epstein** (quando il diametro del corpo è inferiore al cammino libero medio delle molecole di gas) e **Stokes** (per corpi più grandi), smorzando l'eccentricità e l'inclinazione orbitale dei frammenti minori.

### Dinamica degli Urti, Accrescimento e Frammentazione
* **Il fenomeno:** Quando due corpi si intersecano nello spazio, l'esito dello scontro dipende dall'energia cinetica relativa e dalle proprietà meccaniche dei materiali: possono rimbalzare elasticamente/anelasticamente, fondersi (accrescimento) o frantumarsi in uno sciame di detriti.
* **Come funziona:** L'energia d'urto nel sistema di riferimento del centro di massa viene confrontata con soglie di energia critica di legame gravitazionale e strutturale delle particelle coinvolte.
* **Come lo modello:** 
  * **Accrescimento / Cannibalismo:** Se la velocità relativa è sotto la soglia di cattura, i corpi si fondono conservando la massa totale e ricalcolando il raggio equivalente (con una densità che si compatta progressivamente ad ogni fusione).
  * **Rimbalzo:** Tra la soglia di cattura e quella di frammentazione, viene applicato un coefficiente di restituzione per calcolare le velocità post-impatto, conservando la quantità di moto.
  * **Frammentazione:** Se l'energia cinetica supera la soglia critica, il corpo maggiore viene disgregato in un numero controllato di frammenti minori, distribuendo la massa residua e preservando la quantità di moto totale.



### Forze Elettrostatiche (Interazione Coulombiana)
* **Il fenomeno:** Dominanti nella Fase 1 sui grani microscopici di polvere, dove la carica elettrica accumulata (per fotoionizzazione o collisioni) genera attrazione o repulsione elettrostatica a corto raggio.
* **Come funziona:** Regolate dalla legge di Coulomb, diventano del tutto trascurabili su scala macroscopica a causa della neutralità elettrica complessiva dei corpi massicci.
* **Come lo modello:** Come per l'interazione gravitazionale, per gestire migliaia di corpi senza collassare a livello computazionale, il motore adotta un approccio gerarchico basato sull'algoritmo **Barnes-Hut**. Anzi, per ottimizzazione le due forze sono calcolate nello stesso ciclo di gestione dell'algoritmo.


## Architettura e Ottimizzazioni Numeriche
Per garantire prestazioni elevate, mantenendo un alto numero di corpi attivi, il motore adotta le seguenti soluzioni:

* **Griglia Spaziale di Collisione (`CollisionGrid`):** La ricerca dei contatti non avviene per forza bruta, ma sfrutta una suddivisione spaziale a celle che riduce la complessità della rilevazione degli urti limitando la ricerca ai vicini prossimi.
* **Concorrenza e Thread Safety:** Il calcolo delle forze e la risoluzione delle collisioni sono parallelizzati tramite Stream Java multi-core. Nelle sezioni critiche di interazione tra particelle, l'accesso concorrente è regolato da un ordinamento rigoroso basato sugli ID dei corpi per prevenire condizioni di deadlock.
* **Gestione dei Savepoint:** Il motore traccia in tempo reale lo stato delle particelle e metriche globali (fusioni, rimbalzi, frammentazioni, cadute sulla stella), e permette la serializzazione e il ripristino dello stato della simulazione da punti di salvataggio discreti.
* **Interfaccia Grafica e Controlli Interattivi (`SimulationPanel`)**: L'interfaccia si disaccoppia dal ciclo di calcolo fisico tramite snapshot di stato sincronizzati, garantendo fluidità e prestazioni elevate senza pesare sui loop di calcolo. Dal pannello grafico è possibile gestire e visualizzare:
    - **Navigazione e Vista**: Zoom fluido con rotellina, panning con tasto destro e reset della vista tramite doppio click.
    - **Selezione e Camera Lock**: Interazione a click sui corpi celesti con pannello HUD dedicato, inclusa la possibilità di bloccare la telecamera sui corpi d'interesse e attivarne il tracciamento orbitale kepleriano.
    - **Analisi Dinamica del Disco**: Visualizzazione in tempo reale delle orbite dei corpi più massivi, dei solchi radiali a bassa densità e delle fasce di clearing planetario mirate (tasto rapido 'G').
    - **Comandi di Sessione**: Gestione della pausa in tempo reale sincronizzata con il motore ('P') e funzionalità integrata di esportazione istantanea di screenshot in formato PNG (saveScreenshot).


## Definizioni
#### Sfera di Hill
In astrofisica e meccanica celeste, la Sfera di Hill definisce la regione di spazio in cui la gravità di un corpo celeste domina rispetto a quella della stella centrale, determinando il raggio d'azione entro cui il corpo riesce a catturare e mantenere in orbita i propri satelliti o planetesimi.

#### Solco / Gap Protoplanetario
Regione anulare a bassa densità di materiale che si forma nel disco protoplanetario lungo l'orbita di un corpo massiccio (protopianeta), scavata per effetto combinato di risonanze gravitazionali, effetti mareali e interazioni dinamiche con i planetesimi vicini.

#### Regimi di Epstein e Stokes (Attrito del Gas)
Sono i due regimi fisici che descrivono la resistenza aerodinamica esercitata dal gas sul moto dei corpi solidi a seconda delle loro dimensioni rispetto al gas circostante:
- **Regime di Epstein**: Si applica quando il diametro del corpo è inferiore o comparabile al cammino libero medio delle molecole di gas. In questo caso le molecole colpiscono il corpo individualmente.
- **Regime di Stokes**: Si applica quando il corpo è sufficientemente grande rispetto al cammino libero medio delle molecole, permettendo di trattare il gas come un fluido continuo caratterizzato da una propria viscosità.

#### Energia di Legame Gravitazionale e Strutturale
- **Energia di legame strutturale**: La soglia di energia meccanica legata alla coesione interna del materiale solido (roccia, ghiaccio o polvere), fondamentale per resistere agli urti nei corpi di piccola scala dove la gravità è trascurabile.
- **Energia di legame gravitazionale**: L'energia totale necessaria affinché tutti i frammenti di un corpo disgregato superino la mutua attrazione e si allontanino definitivamente nello spazio senza ricadere insieme per effetto della gravità.

#### Softening di Plummer
Parametro geometrico ($\epsilon$) introdotto nel calcolo del potenziale gravitazionale di N-corpi per evitare singolarità numeriche e forze infinite in caso di collisioni o passaggi ravvicinati tra particelle, smussando l'andamento del campo a cortissima distanza.

#### QuadTree e OctTree
Strutture dati geometriche gerarchiche utilizzate negli algoritmi di N-corpi (come Barnes-Hut) per suddiscere lo spazio ricorsivamente in regioni circoscritte:
- **QuadTree**: Struttura bidimensionale in cui ogni nodo dello spazio viene suddiviso in 4 quadranti (quadtree), impiegata per indicizzare e approssimare la distribuzione delle particelle in piani cartesiani.
- **OctTree**: Estensione tridimensionale in cui lo spazio viene suddiviso in 8 ottanti, utilizzata per simulazioni volumetriche complete (sebbene il piano di lavoro principale di Stardust si sviluppi su coordinate planari, l'albero gerarchico modella la suddivisione spaziale dei nodi).

#### Algoritmo Euler-Cromer (Eulero Semi-Implicito)
Algoritmo di integrazione numerica del primo ordine per equazioni differenziali ordinarie (ODE), impiegato nella simulazione per aggiornare posizioni e velocità. A differenza del metodo di Eulero esplicito, calcola prima la velocità aggiornata e utilizza immediatamente quest'ultima per calcolare la nuova posizione.

---


## Collocazione e perimetro di Stardust
Questo motore di simulazione si colloca nella Fase 4. L'architettura non risolve la microfisica di superficie né la fluidodinamica del gas, ma ne include l'effetto dinamico tramite una forza di drag con transizione tra i regimi di Epstein e Stokes. La frammentazione è modellata in forma semplificata, con conservazione della quantità di moto tra i frammenti generati. Il cuore del motore resta l'interazione gravitazionale reciproca, ottimizzata tramite algoritmi gerarchici come Barnes-Hut per scalare in modo efficiente sul numero di corpi.

>Il motore include anche un modello di interazione coulombiana (disattivato di default, poiché ininfluente alle masse tipiche della Fase 4), predisposto come base per un'eventuale estensione futura verso le fasi di coagulazione della polvere.


## Parametri di Simulazione (`parameters.txt` / `SimulationParams`)
Tutti i parametri fisici e numerici sono centralizzati in un file di testo `chiave=valore` caricato a runtime da `SimulationParams`, con due livelli di priorità:

1. **`parameters.txt`**: nella root del progetto, contiene i valori di default per qualunque simulazione.
2. **`simulations/<id>/parameters.txt`**: se presente, sovrascrive *solo* le chiavi che specifica, lasciando invariato tutto il resto. Utile per testare una variante senza toccare la configurazione di default né la simulazione principale in corso.

I gruppi principali (vedi `parameters.txt` per l'elenco completo):

* **Stella centrale**: `centralStarMass`, `centralStarRadius`, `centralStarDensity`.
* **Disco iniziale**: `n` (numero di particelle), `diskInnerRadiusAU` / `diskOuterRadiusAU`, `initialParticleMassMin` / `initialParticleMassMax` e `massPowerLawIndex` (distribuzione a legge di potenza delle masse), `initialParticleDensity`, `initialVelocityDispersion`.
* **Gravità**: `dt` (passo di integrazione, secondi), `softening` (parametro ε di Plummer), `activeGravityModel` (`NEWTONIAN_CLAMPED` o `PLUMMER_SOFTENED`), `useBarnesHut` / `barnesHutTheta` / `barnesHutThreshold` (soglia di N sotto cui si torna al calcolo diretto), `enableElectrostaticForce`.
* **Collisioni**: `hillCaptureFraction` (frazione del raggio di Hill usata come raggio di cattura — vedi nota sotto), `gravitationalCaptureMultiplier`, `mergeVelocityFloor` (soglia minima di fusione indipendente dalla velocità di fuga), `fragmentationMultiplier`.
* **Drag / Gas**: `dragReferenceDensity`, `gasDensityBase`, `gasProfileExponent`.
* **Sessione / I/O**: `logSummaryEveryNSteps`, `screenshotEveryNSteps`, `fps`, `autosaveInterval`.


## Organizzazione di una simulazione
Ogni simulazione (identificata da un `simulationId`, passato come argomento all'avvio o generato automaticamente da timestamp) vive in una cartella propria, isolata dalle altre:

```
simulations/<simulationId>/
  parameters.txt        # override opzionale, solo le chiavi da cambiare rispetto al default
  savepoint.txt         # stato completo (particelle + metriche), aggiornato ad ogni autosave/chiusura
  events.log            # log degli evnti, in append
  runs.log              # una riga START/STOP per ogni sessione (avvio/chiusura del programma)
  screenshots/          # PNG del pannello grafico, salvati periodicamente
```
Questo permette di far girare più simulazioni in parallelo (ognuna con i propri parametri, log e savepoint), riprenderle in sessioni successive senza confusione, e ricostruire a posteriori, dai due log, sia la cronologia fisica degli eventi sia il tempo reale effettivamente investito in ciascuna sessione.


## Savepoint: sessioni persistenti e simulazioni "live-editabili"
Il file `savepoint.txt` (formato testuale: stato globale in chiave=valore, particelle in CSV con posizione, velocità, massa, carica, densità, raggio iniziale, contatore fusioni) non serve solo a interrompere e riprendere una run lunga tra un riavvio e l'altro: essendo un formato testuale semplice, lo stato non è mai legato a una specifica versione compilata del motore. In pratica questo permette di **modificare il codice o i parametri, e ricompilare senza perdere la simulazione in corso**.


## Avvio Simulazione
```bash
mvn clean package

# Simulazione standard (disco protoplanetario), ID generato automaticamente da timestamp:
mvn exec:java -Dexec.mainClass="net.gommagomma.stardust.Stardust"

# Con un ID esplicito (per riprendere una simulazione specifica o tenerne più di una separate):
mvn exec:java -Dexec.mainClass="net.gommagomma.stardust.Stardust" -Dexec.args="mia-simulazione"

# Demo con scenari predefiniti (sistema Terra-Luna, sistema solare con satelliti principali):
mvn exec:java -Dexec.mainClass="net.gommagomma.stardust.demo.SunEarthMoonDemo"
mvn exec:java -Dexec.mainClass="net.gommagomma.stardust.demo.SolarSystemDemo"
```


## Test
Il motore fisico è coperto da una suite di test JUnit 5, organizzata su più livelli: formule isolate (gravità, raggio di Hill), esiti delle collisioni e leggi di conservazione, l'albero di Barnes-Hut confrontato con la somma diretta, l'integrazione orbitale a due e N corpi su periodi lunghi, `SimulationEngine` end-to-end (dispatch sequenziale/parallelo/Barnes-Hut, concorrenza nella risoluzione delle collisioni), ed altro.

```bash
mvn test
```

## Benchmark
Nella cartella dei test sono presenti degli strumenti per misurare il compromesso reale tra `dt`, `theta` di Barnes-Hut e numero di particelle: quanto costa in tempo di calcolo, quanto si paga in fedeltà della forza e in deriva dell'energia. Producono tabelle.

```bash
mvn test-compile
mvn exec:java -Dexec.mainClass="net.gommagomma.stardust.benchmark.DtThetaDurationEnergyMatrix" -Dexec.classpathScope=test
```

Le misure raccolte nel tempo vivono in `benchmarks/`.


## About & License
**Author**: Alessandro Fraschetti (gom9000).  
**License**: This repository is licensed under the [MIT License](LICENSE).
