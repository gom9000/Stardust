StarDust
Type: Astrophysics Simulation | Status: Experimental / Workbench
An exploratory N-body simulation modeling the early stages of protoplanetary formation. It simulates the physical transition from electrostatic interactions between interstellar dust grains to gravity-dominated accretion, incorporating fluid gas drag to observe the emergence of planetesimals.

# Stardust
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

## Collocazione e perimetro di Stardust
Questo motore di simulazione si colloca nella Fase 4. L'architettura non risolve la microfisica di superficie né la fluidodinamica del gas, ma ne include l'effetto dinamico tramite una forza di drag con transizione tra i regimi di Epstein e Stokes. La frammentazione è modellata in forma semplificata, con conservazione della quantità di moto tra i frammenti generati. Il cuore del motore resta l'interazione gravitazionale reciproca, ottimizzata tramite algoritmi gerarchici come Barnes-Hut per scalare in modo efficiente sul numero di corpi.

---

## Definizioni
#### Sfera di Hill
In astrofisica e meccanica celeste, la Sfera di Hill definisce la regione di spazio in cui la gravità di un corpo celeste domina rispetto a quella della stella centrale.

