package net.gommagomma.stardust.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.collision.CollisionResult;

/**
 * Test di "certificazione fisica": a differenza degli altri test (che verificano una formula
 * isolata con numeri scelti per leggibilità), qui si controlla che i valori di DEFAULT della
 * simulazione, applicati a scenari con un significato fisico reale, producano risultati che
 * un astrofisico riconoscerebbe come sensati — o, quando sappiamo che NON lo sono (i satelliti
 * di Giove che vengono distrutti al primo step, scoperto analizzando la demo del sistema solare),
 * lo documentano esplicitamente come comportamento atteso e regressione controllata.
 */
class PhysicalCertificationTest {

    private static Particle particleAt(double x, double y, double z, double mass, double density) {
        return new Particle(new Vector3D(x, y, z), new Vector3D(0, 0, 0), mass, 0.0, density);
    }

    // ---------------------------------------------------------------
    // 1. Grandezze astronomiche note: la velocità circolare a 1 AU deve combaciare con la realtà
    // ---------------------------------------------------------------

    @Test
    void circularVelocityAt1AU_matchesKnownAstronomicalValue() {
        // Valore reale noto per un corpo in orbita circolare a 1 AU attorno a una stella di massa
        // solare: ~29,78 km/s (è la velocità orbitale della Terra). Se questo test fallisse per un
        // fattore diverso da un piccolo arrotondamento, vorrebbe dire che PhysicsConstants.G o
        // SimulationParams.centralStarMass hanno un'unità di misura sbagliata da qualche parte —
        // esattamente il tipo di bug (conversione AU/metri) che abbiamo trovato più volte a mano.
        SimulationParams params = new SimulationParams();
        double r = PhysicsConstants.AU;
        double vCirc = Math.sqrt(PhysicsConstants.G * params.centralStarMass / r);

        double expectedKmS = 29.78;
        assertEquals(expectedKmS, vCirc / 1000.0, 0.05,
                "La velocità orbitale circolare a 1 AU deve combaciare con il valore reale noto (~29,78 km/s), "
                        + "osservato: " + (vCirc / 1000.0) + " km/s");
    }

    @Test
    void hillRadiusOfJupiterLikeBody_matchesKnownAstronomicalOrderOfMagnitude() {
        // Il raggio di Hill reale di Giove è ~0,355 AU (~53 milioni di km). Verifica indipendente
        // che getHillRadius produca il giusto ordine di grandezza con masse/distanze reali.
        SimulationParams params = new SimulationParams();
        Physics physics = new Physics(params);

        double rJupiter = 5.203 * PhysicsConstants.AU;
        double massJupiter = 1.898e27;
        Particle jupiter = particleAt(rJupiter, 0, 0, massJupiter, 1326.0);

        double hillRadiusAU = physics.getHillRadius(jupiter) / PhysicsConstants.AU;

        assertEquals(0.355, hillRadiusAU, 0.02,
                "Il raggio di Hill di un corpo massa/distanza-Giove deve combaciare col valore reale noto (~0,355 AU), "
                        + "osservato: " + hillRadiusAU + " AU");
    }

    // ---------------------------------------------------------------
    // 2. Certificazione degli esiti di collisione su scenari fisicamente nominati
    // ---------------------------------------------------------------

    @Test
    void slowRubblePileEncounter_resultsInMerge() {
        // Due corpi di scala asteroidale (raggio fisico ~4,3 km per m=1e15 kg a densità 3000 kg/m^3).
        // A 1 AU dalla stella il raggio di cattura effettivo e' dominato dal raggio di Hill (non dal
        // raggio fisico: verificato con getEffectiveCaptureRadius, ~165 km per corpo anche a questa
        // massa "piccola", perche' il raggio di Hill cresce linearmente con la distanza dalla stella).
        // A quella distanza la velocità di fuga reciproca e' minuscola (<1 m/s), quindi la soglia di
        // fusione e' in pratica il pavimento fisso dustCohesionThreshold=2,5 m/s. Un incontro lento
        // (2 m/s, sotto quel pavimento) e' esattamente il regime di accrescimento "soft landing" da
        // cui nascono i planetesimi.
        SimulationParams params = new SimulationParams();
        Physics physics = new Physics(params);

        double m = 1e15;
        Particle p1 = particleAt(PhysicsConstants.AU, 0, 0, m, 3000.0);
        Particle p2 = particleAt(PhysicsConstants.AU, 0, 0, m, 3000.0);
        p1.setVelocity(new Vector3D(1.0, 0, 0));
        p2.setVelocity(new Vector3D(-1.0, 0, 0)); // v_rel = 2 m/s

        assertEquals(CollisionResult.MERGE, physics.evaluateCollision(p1, p2),
                "Un incontro lento tra corpi di scala asteroidale (sotto la soglia di fusione dustCohesionThreshold) deve risultare in fusione");
    }

    @Test
    void moderateImpact_resultsInBounce() {
        // Stessa coppia di corpi, velocità relativa moderata (3,5 m/s): sopra la soglia di fusione
        // (il pavimento dustCohesionThreshold=2,5 m/s -- a questa distanza dalla stella il raggio
        // di cattura e' dominato dal raggio di Hill, non dal raggio fisico, quindi la velocità di
        // fuga reciproca calcolata li' e' minuscola e la soglia di fusione la ignora in favore del
        // pavimento fisso) ma sotto quella di frammentazione (5,0 m/s) — un urto che deflette le
        // traiettorie senza né fondere né distruggere i corpi.
        SimulationParams params = new SimulationParams();
        Physics physics = new Physics(params);

        double m = 1e15;
        Particle p1 = particleAt(PhysicsConstants.AU, 0, 0, m, 3000.0);
        Particle p2 = particleAt(PhysicsConstants.AU, 0, 0, m, 3000.0);
        p1.setVelocity(new Vector3D(1.75, 0, 0));
        p2.setVelocity(new Vector3D(-1.75, 0, 0)); // v_rel = 3.5 m/s

        assertEquals(CollisionResult.BOUNCE, physics.evaluateCollision(p1, p2),
                "Un urto a velocità moderata (tra le due soglie) deve risultare in un rimbalzo, non in fusione o frammentazione");
    }

    @Test
    void catastrophicImpact_resultsInFragmentation() {
        // Velocità relativa molto alta (50 m/s, oltre 4x la soglia di frammentazione): un impatto
        // energetico che nella realtà distruggerebbe entrambi i corpi in una nube di detriti.
        SimulationParams params = new SimulationParams();
        Physics physics = new Physics(params);

        double m = 1e15;
        Particle p1 = particleAt(PhysicsConstants.AU, 0, 0, m, 3000.0);
        Particle p2 = particleAt(PhysicsConstants.AU, 0, 0, m, 3000.0);
        p1.setVelocity(new Vector3D(25.0, 0, 0));
        p2.setVelocity(new Vector3D(-25.0, 0, 0)); // v_rel = 50 m/s

        assertEquals(CollisionResult.FRAGMENT, physics.evaluateCollision(p1, p2),
                "Un impatto ad alta energia deve risultare in una frammentazione catastrofica");
    }

    // ---------------------------------------------------------------
    // 3. Certificazione (e regressione documentata) sulla sopravvivenza dei satelliti reali
    // ---------------------------------------------------------------

    @Test
    void earthMoonSystem_survivesInitialCollisionCheck_withDefaultParams() {
        // Con i parametri di default (hillCaptureFraction=0.20), la distanza Terra-Luna reale
        // (384.400 km) e' di POCO superiore al raggio di cattura combinato Terra+Luna (~368.300 km,
        // calcolato a mano e verificato analizzando la demo del sistema solare) — un margine
        // stretto ma sufficiente perché checkCollision non scatti al primo istante. Questo test
        // certifica che questo margine resta positivo con i parametri di default correnti: se un
        // giorno qualcuno alzasse hillCaptureFraction anche di poco, questo test fallirebbe SUBITO,
        // invece di scoprirlo per caso aprendo la demo e notando che la Luna e' sparita.
        SimulationParams params = new SimulationParams();
        Physics physics = new Physics(params);

        double starPlanetDist = 1.0 * PhysicsConstants.AU;
        double planetMoonDist = 3.844e8; // distanza Terra-Luna reale, m

        Particle earth = particleAt(starPlanetDist, 0, 0, 5.972e24, 5500.0);
        Particle moon = particleAt(starPlanetDist + planetMoonDist, 0, 0, 7.342e22, 3340.0);

        assertFalse(physics.checkCollision(earth, moon),
                "Con i parametri di default, la Luna alla sua distanza orbitale reale NON deve scattare come collisione "
                        + "immediata con la Terra (il margine e' stretto: se questo fallisce, verificare hillCaptureFraction)");
    }

    @Test
    void jupiterIoSystem_triggersImmediateCollision_withDefaultParams_documentedLimitation() {
        // Regressione DOCUMENTATA, non un comportamento desiderabile: con hillCaptureFraction=0.20,
        // il raggio di cattura di Giove (~10,6 milioni di km) e' enormemente più grande della
        // distanza orbitale reale di Io (~422.000 km) — Io scatta come collisione immediata al
        // primissimo step, molto prima di poter essere osservata come satellite separato. E' il
        // motivo per cui la demo del sistema solare richiede un parameters.txt dedicato con
        // hillCaptureFraction molto più basso per mostrare i satelliti di Giove. Questo test
        // certifica che il comportamento (oggi non fisicamente realistico per lune di pianeti
        // giganti) resta noto e intenzionale con i parametri di default, non una sorpresa silenziosa.
        SimulationParams params = new SimulationParams();
        Physics physics = new Physics(params);

        double starPlanetDist = 5.203 * PhysicsConstants.AU;
        double planetMoonDist = 4.217e8; // distanza Giove-Io reale, m

        Particle jupiter = particleAt(starPlanetDist, 0, 0, 1.898e27, 1326.0);
        Particle io = particleAt(starPlanetDist + planetMoonDist, 0, 0, 8.932e22, 3528.0);

        assertTrue(physics.checkCollision(jupiter, io),
                "Comportamento noto e documentato: con hillCaptureFraction di default, Io alla sua distanza reale "
                        + "scatta come collisione immediata con Giove (i satelliti dei giganti gassosi richiedono "
                        + "un hillCaptureFraction ridotto in parameters.txt specifico della simulazione)");
    }

    // ---------------------------------------------------------------
    // 4. Regressione sulla conversione di unità AU->metri nel caricamento dei parametri
    // ---------------------------------------------------------------

    @Test
    void loadingDiskRadiusFromFile_convertsAUToMetersCorrectly(@TempDir Path tempDir) throws IOException {
        // Regressione diretta sul bug trovato più volte in questa stessa conversazione
        // (diskInnerRadiusAU/diskOuterRadiusAU scritti nel file in AU, ma usati altrove in metri
        // senza conversione, o convertiti due volte). Certifica che, dopo il caricamento, il campo
        // in metri combaci ESATTAMENTE con valoreAU * PhysicsConstants.AU, non un valore ancora in
        // AU né raddoppiato.
        Path file = tempDir.resolve("params-test.txt");
        Files.writeString(file, "diskInnerRadiusAU=0.4\ndiskOuterRadiusAU=2.5\n");

        SimulationParams params = new SimulationParams();
        params.load(file.toFile());

        assertEquals(0.4 * PhysicsConstants.AU, params.diskInnerRadius, 1.0,
                "diskInnerRadius deve essere il valore in AU del file moltiplicato per PhysicsConstants.AU, non il valore grezzo ne' convertito due volte");
        assertEquals(2.5 * PhysicsConstants.AU, params.diskOuterRadius, 1.0,
                "diskOuterRadius deve essere il valore in AU del file moltiplicato per PhysicsConstants.AU, non il valore grezzo ne' convertito due volte");

        // Controllo di sanità aggiuntivo: il valore in metri deve essere enormemente più grande
        // del valore grezzo in AU (altrimenti la conversione non e' avvenuta affatto).
        assertTrue(params.diskOuterRadius > 1000.0,
                "diskOuterRadius in metri deve essere dell'ordine di 10^11, non un piccolo numero come 2.5 (AU non convertito)");
    }
}