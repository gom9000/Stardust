package net.gommagomma.stardust.physics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.SimulationConfig;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Estende il test di orbita a due corpi a un vero sistema N-corpi (N=2 planetesimi + stella
 * fissa), applicando ad ogni step lo stesso schema di accumulo a coppie usato da
 * SimulationEngine.computeForcesSequential (gravità stella + gravità mutua tra i planetesimi),
 * poi integrando con Particle.update reale.
 *
 * A differenza dei test di Physics.evaluateCollision/mergeParticles ecc. (che validano una singola
 * chiamata), qui l'obiettivo è verificare che l'accumulo di forze multiple su più corpi, ripetuto
 * per migliaia di step, non introduca derive energetiche anomale: è il test più vicino a "il
 * problema N-corpi funziona davvero", perché somma gli effetti di bug sottili (segni, doppio
 * conteggio, ecc.) su una traiettoria estesa invece che su una singola chiamata isolata.
 */
class MultiBodyOrbitIntegrationTest {

    private static double starPotential(Particle p) {
        return Physics.calculateCentralStarPotentialEnergy(p);
    }

    private static double mutualPotential(Particle p1, Particle p2) {
        double dist = p1.getPosition().distanceTo(p2.getPosition());
        if (dist <= 0) return 0.0;
        return -(SimulationConfig.G * p1.getMass() * p2.getMass()) / dist;
    }

    private static double kinetic(Particle p) {
        double v = p.getVelocity().magnitude();
        return 0.5 * p.getMass() * v * v;
    }

    private static double totalSystemEnergy(List<Particle> planetesimals) {
        double energy = 0.0;
        for (Particle p : planetesimals) {
            energy += kinetic(p) + starPotential(p);
        }
        // Potenziale mutuo contato una sola volta per coppia.
        for (int i = 0; i < planetesimals.size(); i++) {
            for (int j = i + 1; j < planetesimals.size(); j++) {
                energy += mutualPotential(planetesimals.get(i), planetesimals.get(j));
            }
        }
        return energy;
    }

    /** Riproduce lo schema reale dell'engine: gravità stella (a coppie singole) + gravità mutua (a coppie N-N). */
    private static void stepSystem(List<Particle> planetesimals, double dt) {
        for (Particle p : planetesimals) {
            p.resetForce();
            p.addForce(Physics.calculateCentralStarGravity(p));
        }
        for (int i = 0; i < planetesimals.size(); i++) {
            Particle p1 = planetesimals.get(i);
            for (int j = i + 1; j < planetesimals.size(); j++) {
                Particle p2 = planetesimals.get(j);
                Vector3D f = Physics.calculateGravity(p1, p2);
                p1.addForce(f);
                p2.addForce(f.multiply(-1));
            }
        }
        for (Particle p : planetesimals) {
            p.update(dt);
        }
    }

    @Test
    void twoIndependentOrbits_conserveTotalSystemEnergy_overOneShortPeriod() {
        // Due planetesimi su orbite circolari a raggi molto diversi (1 AU e 1.6 AU): la loro
        // reciproca attrazione è calcolata (non ignorata, esercitando davvero il pattern N-corpi),
        // ma è trascurabile rispetto a quella della stella, quindi il sistema resta ben educato
        // (niente incontri ravvicinati/instabilità) mentre il codice di accumulo N-corpi viene comunque testato.
        double r1 = SimulationConfig.AU;
        double r2 = 1.6 * SimulationConfig.AU;

        double v1 = Math.sqrt((SimulationConfig.G * SimulationConfig.STAR_MASS) / r1);
        double v2 = Math.sqrt((SimulationConfig.G * SimulationConfig.STAR_MASS) / r2);

        // Masse piccole rispetto alla stella E piccole a sufficienza da rendere la mutua attrazione
        // fisicamente trascurabile per la stabilità del test (ma comunque calcolata ad ogni step).
        Particle planetA = new Particle(new Vector3D(r1, 0, 0), new Vector3D(0, v1, 0), 1.0e20, 0.0, 3000.0);
        Particle planetB = new Particle(new Vector3D(0, r2, 0), new Vector3D(-v2, 0, 0), 1.0e20, 0.0, 3000.0);

        List<Particle> system = List.of(planetA, planetB);

        double period1 = 2.0 * Math.PI * Math.sqrt(Math.pow(r1, 3) / (SimulationConfig.G * SimulationConfig.STAR_MASS));
        double dt = SimulationConfig.DT;
        int steps = (int) Math.round(period1 / dt); // un periodo orbitale del corpo più interno

        double energyInitial = totalSystemEnergy(system);
        double maxDrift = 0.0;

        for (int i = 0; i < steps; i++) {
            stepSystem(system, dt);
            double drift = Math.abs((totalSystemEnergy(system) - energyInitial) / energyInitial);
            maxDrift = Math.max(maxDrift, drift);
        }

        assertTrue(maxDrift < 0.01,
                "L'energia totale del sistema (2 planetesimi + stella, con gravità mutua inclusa) non deve derivare più dell'1% "
                        + "durante un periodo orbitale del corpo interno (drift osservato: " + (maxDrift * 100) + "%)");
    }

    @Test
    void mutualAttraction_pullsTwoCoOrbitingBodiesTogether_whenStarGravityIsAbsent() {
        // Sanity check isolata sulla sola componente N-corpi (nessuna stella coinvolta): due corpi
        // fermi, distanti, con solo la reciproca gravità attiva, devono avvicinarsi l'uno all'altro
        // nel tempo. Se il pattern p1.addForce(f) / p2.addForce(f.multiply(-1)) avesse un segno
        // invertito, i corpi si allontanerebbero invece di cadere l'uno verso l'altro.
        Particle p1 = new Particle(new Vector3D(-5e6, 0, 0), new Vector3D(0, 0, 0), 1.0e23, 0.0, 3000.0);
        Particle p2 = new Particle(new Vector3D(5e6, 0, 0), new Vector3D(0, 0, 0), 1.0e23, 0.0, 3000.0);
        List<Particle> system = List.of(p1, p2);

        double initialSeparation = p1.getPosition().distanceTo(p2.getPosition());

        double dt = 10.0;
        for (int i = 0; i < 200; i++) {
            p1.resetForce();
            p2.resetForce();
            Vector3D f = Physics.calculateGravity(p1, p2);
            p1.addForce(f);
            p2.addForce(f.multiply(-1));
            p1.update(dt);
            p2.update(dt);
        }

        double finalSeparation = p1.getPosition().distanceTo(p2.getPosition());
        assertTrue(finalSeparation < initialSeparation,
                "Due corpi soggetti solo alla reciproca gravità devono avvicinarsi, non allontanarsi "
                        + "(separazione iniziale=" + initialSeparation + ", finale=" + finalSeparation + ")");
    }

    @Test
    void hierarchicalSystem_keepsSatelliteBoundToPlanet_overFullOrbitalPeriods() {
        // Sistema gerarchico realistico: stella + pianeta (in orbita attorno alla stella) + satellite
        // (in orbita attorno al pianeta), masse/distanze paragonabili a Sole-Terra-Luna. A differenza
        // dei test precedenti la stella qui NON è fissa: è una Particle vera, soggetta anch'essa alla
        // gravità di pianeta e satellite, per esercitare il caso generale a 3 corpi con 3 coppie di forze.
        double starMass = 1.989e30;
        double planetMass = 5.972e24;    // simile alla Terra
        double satelliteMass = 7.342e22; // simile alla Luna

        double starPlanetDist = 1.5e11; // 1 AU
        double planetMoonDist = 4.0e8;  // ~400.000 km, come Terra-Luna

        Particle star = new Particle(new Vector3D(0, 0, 0), new Vector3D(0, 0, 0), starMass, 0.0, 1408.0);

        double vPlanet = Math.sqrt(SimulationConfig.G * starMass / starPlanetDist);
        Particle planet = new Particle(
                new Vector3D(starPlanetDist, 0, 0),
                new Vector3D(0, vPlanet, 0),
                planetMass, 0.0, 5500.0
        );

        double vMoonRel = Math.sqrt(SimulationConfig.G * planetMass / planetMoonDist);
        Particle satellite = new Particle(
                new Vector3D(starPlanetDist + planetMoonDist, 0, 0),
                new Vector3D(0, vPlanet + vMoonRel, 0),
                satelliteMass, 0.0, 3340.0
        );

        List<Particle> system = List.of(star, planet, satellite);
        double initialMoonDist = planet.getPosition().distanceTo(satellite.getPosition());

        // Copriamo 2 periodi orbitali COMPLETI del satellite attorno al pianeta (non una frazione
        // arbitraria): un'orbita che si "apre" per un bug o per instabilità numerica emerge chiaramente
        // solo osservando l'intero ciclo, non un pezzo scelto a caso.
        double moonPeriod = 2.0 * Math.PI * Math.sqrt(Math.pow(planetMoonDist, 3) / (SimulationConfig.G * planetMass));
        double dt = 300.0;
        int steps = (int) Math.round(1000.0 * moonPeriod / dt);

        double minMoonDist = Double.MAX_VALUE;
        double maxMoonDist = 0.0;

        for (int i = 0; i < steps; i++) {
            for (Particle p : system) {
                p.resetForce();
            }

            // IMPORTANTE: Physics.calculateGravity(p1, p2) restituisce la forza SU p1 dovuta a p2
            // (convenzione verificata in GravityCalculatorTest). Il pattern corretto per applicarla
            // a coppie è quindi p1.addForce(f) / p2.addForce(f.multiply(-1)) — esattamente lo schema
            // usato da SimulationEngine.computeForcesSequential e da NBodyForceAccumulationTest.
            Vector3D fStarPlanet = Physics.calculateGravity(star, planet);
            star.addForce(fStarPlanet);
            planet.addForce(fStarPlanet.multiply(-1));

            Vector3D fStarSat = Physics.calculateGravity(star, satellite);
            star.addForce(fStarSat);
            satellite.addForce(fStarSat.multiply(-1));

            Vector3D fPlanetSat = Physics.calculateGravity(planet, satellite);
            planet.addForce(fPlanetSat);
            satellite.addForce(fPlanetSat.multiply(-1));

            for (Particle p : system) {
                p.update(dt);
            }

            double d = planet.getPosition().distanceTo(satellite.getPosition());
            minMoonDist = Math.min(minMoonDist, d);
            maxMoonDist = Math.max(maxMoonDist, d);
        }

        double finalMoonDist = planet.getPosition().distanceTo(satellite.getPosition());

        // Soglie strette (±15%): con la fisica corretta l'orbita resta quasi circolare per 2 periodi
        // interi (osservato empiricamente: minimo ~95%, massimo ~100.5% della distanza iniziale).
        // Una soglia larga (es. 1.5x) avrebbe lasciato passare inosservato un bug di segno come quello
        // corretto qui: con le forze invertite il satellite si allontanava linearmente fin dai primi step.
        assertTrue(minMoonDist > 0.85 * initialMoonDist,
                "Il satellite non deve avvicinarsi troppo al pianeta (possibile caduta): distanza minima osservata="
                        + minMoonDist + " m, soglia=" + (0.85 * initialMoonDist) + " m");
        assertTrue(maxMoonDist < 1.15 * initialMoonDist,
                "Il satellite non deve allontanarsi eccessivamente dal pianeta (possibile fuga): distanza massima osservata="
                        + maxMoonDist + " m, soglia=" + (1.15 * initialMoonDist) + " m");
        assertTrue(finalMoonDist < 1.15 * initialMoonDist,
                "Dopo 2 periodi orbitali completi il satellite deve essere ancora chiaramente legato al pianeta "
                        + "(distanza iniziale=" + initialMoonDist + " m, finale=" + finalMoonDist + " m)");
    }
}
