package net.gommagomma.stardust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.gommagomma.stardust.io.RunLogger;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.Physics;

/**
 * Conservazione di massa, energia e quantità di moto attraverso SimulationEngine.step() VERO
 * (non un ciclo di forze scritto a mano come in MultiBodyOrbitIntegrationTest) — dispatch reale,
 * integrazione reale, nessuna scorciatoia.
 *
 * Due scenari distinti, apposta separati:
 * - massa+energia: un piccolo sistema planetario legato alla stella, con il drag del gas
 *   DISATTIVATO (gasDensityBase=0) perché il drag dissipa energia orbitale per progettazione,
 *   non per bug — con il drag attivo l'energia NON deve conservarsi, quindi va escluso per
 *   isolare la sola dinamica gravitazionale conservativa.
 * - quantità di moto: un cluster isolato, lontano dalla stella abbastanza da rendere la sua
 *   attrazione trascurabile nella finestra di test, dove a dominare è la mutua gravità tra le
 *   particelle — l'unico regime in cui la quantità di moto TOTALE del sottosistema di particelle
 *   deve conservarsi (con la stella come campo esterno fisso, la sua attrazione altera
 *   legittimamente la quantità di moto delle particelle, quindi non è testabile in sua presenza).
 */
class EngineConservationTest {

    private RunLogger newLogger(Path tempDir, String name) throws IOException {
        return new RunLogger(tempDir.resolve(name + ".log"));
    }

    private static Particle particleAt(double x, double y, double z, double vx, double vy, double vz, double mass) {
        return new Particle(new Vector3D(x, y, z), new Vector3D(vx, vy, vz), mass, 0.0, 3000.0);
    }

    private static double mutualPotential(Particle p1, Particle p2) {
        double dist = p1.getPosition().distanceTo(p2.getPosition());
        if (dist <= 0) return 0.0;
        return -(PhysicsConstants.G * p1.getMass() * p2.getMass()) / dist;
    }

    private static double kinetic(Particle p) {
        double v = p.getVelocity().magnitude();
        return 0.5 * p.getMass() * v * v;
    }

    private static double starPotential(Particle p, double centralStarMass) {
        double r = p.getPosition().magnitude();
        if (r <= 0) return 0.0;
        return -(PhysicsConstants.G * centralStarMass * p.getMass()) / r;
    }

    private static double totalSystemEnergy(List<Particle> particles, double centralStarMass) {
        double energy = 0.0;
        for (Particle p : particles) {
            energy += kinetic(p) + starPotential(p, centralStarMass);
        }
        for (int i = 0; i < particles.size(); i++) {
            for (int j = i + 1; j < particles.size(); j++) {
                energy += mutualPotential(particles.get(i), particles.get(j));
            }
        }
        return energy;
    }

    private static Vector3D totalMomentum(List<Particle> particles) {
        Vector3D p = new Vector3D(0, 0, 0);
        for (Particle particle : particles) {
            p = p.add(particle.getVelocity().multiply(particle.getMass()));
        }
        return p;
    }

    private static double totalMass(List<Particle> particles) {
        double m = 0.0;
        for (Particle p : particles) m += p.getMass();
        return m;
    }

    // ---------------------------------------------------------------
    // Massa + energia: sistema legato, drag disattivato, nessuna collisione
    // ---------------------------------------------------------------

    @Test
    void massAndEnergy_areConserved_overManySteps_withDragDisabled_andNoCollisions(@TempDir Path tempDir) throws IOException {
        SimulationParams params = TestParams.defaults();
        params.gasDensityBase = 0.0; // drag disattivato: la dinamica resta puramente conservativa

        double r1 = PhysicsConstants.AU;
        double r2 = 1.7 * PhysicsConstants.AU;
        double r3 = 2.4 * PhysicsConstants.AU;

        Particle p1 = orbitingPlanet(r1, 0.0, params.centralStarMass, 1.0e20);
        Particle p2 = orbitingPlanet(r2, Math.PI * 0.66, params.centralStarMass, 1.2e20);
        Particle p3 = orbitingPlanet(r3, Math.PI * 1.33, params.centralStarMass, 0.8e20);

        List<Particle> particles = new ArrayList<>(List.of(p1, p2, p3));

        SimulationEngine engine = new SimulationEngine(particles, newLogger(tempDir, "energy"), params);

        double massBefore = totalMass(engine.getParticles());
        double energyBefore = totalSystemEnergy(engine.getParticles(), params.centralStarMass);

        // Un periodo orbitale circa del corpo più interno: abbastanza per far girare la dinamica
        // gravitazionale reale attraverso il dispatch vero dell'engine, senza collisioni (corpi
        // ben separati per raggio e fase orbitale).
        double period1 = 2.0 * Math.PI * Math.sqrt(Math.pow(r1, 3) / (PhysicsConstants.G * params.centralStarMass));
        int steps = (int) Math.round(period1 / params.dt);

        double maxEnergyDrift = 0.0;
        for (int i = 0; i < steps; i++) {
            engine.step();
            double energyNow = totalSystemEnergy(engine.getParticles(), params.centralStarMass);
            maxEnergyDrift = Math.max(maxEnergyDrift, Math.abs((energyNow - energyBefore) / energyBefore));
        }

        double massAfter = totalMass(engine.getParticles());
        double energyAfter = totalSystemEnergy(engine.getParticles(), params.centralStarMass);

        assertEquals(massBefore, massAfter, massBefore * 1e-9,
                "La massa totale deve conservarsi esattamente (nessuna collisione attesa in questo scenario)");
        assertTrue(maxEnergyDrift < 0.01,
                "Con il drag disattivato e nessuna collisione, l'energia totale non deve derivare più dell'1% "
                        + "su un periodo orbitale (drift massimo osservato: " + (maxEnergyDrift * 100) + "%, "
                        + "finale: " + (Math.abs((energyAfter - energyBefore) / energyBefore) * 100) + "%)");
    }

    private static Particle orbitingPlanet(double r, double theta, double centralStarMass, double mass) {
        double x = r * Math.cos(theta);
        double y = r * Math.sin(theta);
        double v = Math.sqrt(PhysicsConstants.G * centralStarMass / r);
        double vx = -v * Math.sin(theta);
        double vy = v * Math.cos(theta);
        return particleAt(x, y, 0, vx, vy, 0, mass);
    }

    // ---------------------------------------------------------------
    // Quantità di moto: l'unica fonte di variazione deve essere la spinta esterna della stella
    // ---------------------------------------------------------------

    @Test
    void momentumChange_isFullyAccountedForByStarPull_withZeroResidualFromMutualForces(@TempDir Path tempDir) throws IOException {
        // La quantità di moto TOTALE del sistema di particelle non si conserva in senso stretto
        // attraverso il motore reale: la stella è un campo esterno fisso (non reagisce, non si
        // sposta), quindi la sua attrazione altera legittimamente la quantità di moto del
        // sottosistema di particelle — non è un bug, è il modello scelto (la stella è troppo
        // massiccia per reagire in modo apprezzabile).
        //
        // La legge fisica corretta da verificare è allora il teorema dell'impulso: la variazione
        // TOTALE di quantità di moto deve coincidere con l'impulso della sola forza stellare,
        // senza alcun residuo dalla gravità mutua tra le particelle -- che, per la terza legge di
        // Newton, deve cancellarsi esattamente a coppie. Un residuo non trascurabile qui
        // indicherebbe esattamente il tipo di bug di segno nell'applicazione della forza mutua che
        // abbiamo già trovato una volta a mano nel sistema gerarchico stella-pianeta-satellite.
        SimulationParams params = TestParams.defaults();
        params.hillCaptureFraction = 1e-8; // niente fusioni spurie a interferire col bilancio
        params.dt = 20.0;

        // Cluster molto lontano dalla stella (10000 AU) ma con masse ravvicinate tra loro (decine
        // di migliaia di km): l'attrazione stellare resta comunque presente e viene applicata dal
        // motore ad ogni step (correttamente), ma qui interessa verificare che sia l'UNICA fonte
        // di variazione della quantità di moto, non che sia trascurabile in assoluto.
        double clusterCenter = 10000.0 * PhysicsConstants.AU;
        Particle p1 = particleAt(clusterCenter - 3e7, 0, 0, 0, 0, 0, 1.2e21);
        Particle p2 = particleAt(clusterCenter + 3e7, 2e7, 0, 0, 0, 0, 1.0e21);
        Particle p3 = particleAt(clusterCenter, -3e7, 0, 0, 0, 0, 0.8e21);

        List<Particle> particles = new ArrayList<>(List.of(p1, p2, p3));
        Physics physics = new Physics(params);

        // Impulso atteso dalla sola stella, valutato alle posizioni iniziali: approssimazione valida
        // perché nella finestra di test le posizioni si spostano di appena ~80 m su una separazione
        // di 3e7 m (verificato) -- la direzione/modulo della forza stellare resta quindi quasi costante.
        Vector3D starForceInitial = new Vector3D(0, 0, 0);
        for (Particle p : particles) {
            starForceInitial = starForceInitial.add(physics.calculateCentralStarGravity(p));
        }

        SimulationEngine engine = new SimulationEngine(particles, newLogger(tempDir, "momentum"), params);

        Vector3D momentumBefore = totalMomentum(engine.getParticles());
        double massBefore = totalMass(engine.getParticles());

        int steps = 100;
        for (int i = 0; i < steps; i++) {
            engine.step();
        }

        Vector3D momentumAfter = totalMomentum(engine.getParticles());
        double massAfter = totalMass(engine.getParticles());

        assertEquals(massBefore, massAfter, massBefore * 1e-9, "La massa totale deve conservarsi esattamente");

        Vector3D actualDelta = momentumAfter.subtract(momentumBefore);
        Vector3D expectedStarImpulse = starForceInitial.multiply(steps * params.dt);
        Vector3D residual = actualDelta.subtract(expectedStarImpulse);

        double residualRatio = residual.magnitude() / actualDelta.magnitude();

        assertTrue(residualRatio < 0.01,
                "La variazione di quantità di moto totale deve essere spiegata quasi interamente dall'impulso "
                        + "della sola forza stellare, con un residuo trascurabile dalla gravità mutua (che deve "
                        + "cancellarsi a coppie per la terza legge di Newton). Residuo osservato: "
                        + (residualRatio * 100) + "% della variazione totale (un valore alto indicherebbe un bug "
                        + "di segno nell'applicazione della forza mutua nel dispatch dell'engine)");
    }
}
