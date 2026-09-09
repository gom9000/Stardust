package net.gommagomma.stardust.physics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.SimulationConfig;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Test di integrazione "leggero": un singolo planetesimo in orbita circolare attorno alla stella
 * centrale, integrato con l'Euler-Cromer reale (Particle.update) e la forza reale
 * (Physics.calculateCentralStarGravity), SENZA passare per SimulationEngine (niente Barnes-Hut,
 * drag, collisioni: qui vogliamo isolare SOLO integratore + forza gravitazionale).
 *
 * Questo tipo di test è più potente dei singoli test unitari sulla formula della forza, perché
 * valida insieme forza e integratore su una traiettoria estesa: se c'è un errore di segno, di
 * scala, o un DT troppo grande, l'energia e il momento angolare iniziano a derivare visibilmente
 * nell'arco di un'orbita, anche se la formula della forza isolata "sembra" corretta.
 */
class TwoBodyOrbitIntegrationTest {

    /** Fa avanzare la particella di un intero step usando solo la gravità della stella centrale. */
    private static void stepUnderStarGravity(Particle p, double dt) {
        p.resetForce();
        p.addForce(Physics.calculateCentralStarGravity(p));
        p.update(dt);
    }

    private static double totalEnergy(Particle p) {
        double v = p.getVelocity().magnitude();
        double kinetic = 0.5 * p.getMass() * v * v;
        double potential = Physics.calculateCentralStarPotentialEnergy(p);
        return kinetic + potential;
    }

    private static double angularMomentumZ(Particle p) {
        // L_z = m * (x*vy - y*vx): componente z del momento angolare, sufficiente per un'orbita nel piano xy.
        Vector3D r = p.getPosition();
        Vector3D v = p.getVelocity();
        return p.getMass() * (r.getX() * v.getY() - r.getY() * v.getX());
    }

    @Test
    void circularOrbit_conservesEnergyAndAngularMomentum_overOneFullPeriod() {
        // Orbita circolare a r0 = 1 AU: per un'orbita circolare v0 = sqrt(G*M_star/r0), diretta
        // perpendicolarmente al raggio (tangenziale), cosi' la forza centripeta e' esattamente
        // quella gravitazionale e l'orbita si chiude su se stessa.
        double r0 = SimulationConfig.AU;
        double vCirc = Math.sqrt((SimulationConfig.G * SimulationConfig.STAR_MASS) / r0);

        // Massa "di prova" trascurabile rispetto alla stella: niente reazione sulla stella (STAR e' fissa
        // nel modello, coerente con calculateCentralStarGravity/PotentialEnergy che trattano la stella
        // come sorgente fissa di campo).
        Particle planetesimal = new Particle(
                new Vector3D(r0, 0, 0),
                new Vector3D(0, vCirc, 0),
                1.0e20, // kg: piccolo rispetto a STAR_MASS (~2e30 kg)
                0.0,
                3000.0
        );

        double period = 2.0 * Math.PI * Math.sqrt(Math.pow(r0, 3) / (SimulationConfig.G * SimulationConfig.STAR_MASS));
        double dt = SimulationConfig.DT;
        int steps = (int) Math.round(period / dt);

        double energyInitial = totalEnergy(planetesimal);
        double angularMomentumInitial = angularMomentumZ(planetesimal);

        double maxEnergyDrift = 0.0;
        double maxAngularMomentumDrift = 0.0;

        for (int i = 0; i < steps; i++) {
            stepUnderStarGravity(planetesimal, dt);

            double energyDrift = Math.abs((totalEnergy(planetesimal) - energyInitial) / energyInitial);
            double angMomDrift = Math.abs((angularMomentumZ(planetesimal) - angularMomentumInitial) / angularMomentumInitial);

            maxEnergyDrift = Math.max(maxEnergyDrift, energyDrift);
            maxAngularMomentumDrift = Math.max(maxAngularMomentumDrift, angMomDrift);
        }

        // Tolleranze larghe (1%): Euler-Cromer e' solo del primo ordine e con questo DT introduce
        // un errore di troncamento non nullo per step; l'obiettivo qui non e' la precisione numerica
        // in se', ma verificare che non ci sia una deriva SISTEMATICA grossolana (segno sbagliato,
        // fattore di scala errato, ecc.) che farebbe esplodere l'errore ben oltre l'1%.
        assertTrue(maxEnergyDrift < 0.01,
                "L'energia totale non deve derivare più dell'1% durante un'orbita completa (drift osservato: "
                        + (maxEnergyDrift * 100) + "%)");
        assertTrue(maxAngularMomentumDrift < 0.01,
                "Il momento angolare non deve derivare più dell'1% durante un'orbita completa (drift osservato: "
                        + (maxAngularMomentumDrift * 100) + "%)");

        // Verifica di chiusura dell'orbita: dopo un periodo, la particella deve essere tornata
        // vicino alla posizione di partenza (r0, 0, 0), non essersi allontanata o essere caduta verso la stella.
        double finalDistanceFromStart = planetesimal.getPosition().distanceTo(new Vector3D(r0, 0, 0));
        assertTrue(finalDistanceFromStart < 0.05 * r0,
                "Dopo un periodo orbitale completo la particella deve tornare vicino al punto di partenza "
                        + "(distanza residua: " + finalDistanceFromStart + " m, soglia: " + (0.05 * r0) + " m)");
    }

    @Test
    void radialInfall_particleFallsInward_whenStartedAtRestOffCenter() {
        // Sanity check di segno: una particella ferma (non in orbita) deve cadere VERSO la stella,
        // non allontanarsi. Se la forza avesse il segno sbagliato, questo test lo scoprirebbe subito.
        double r0 = SimulationConfig.AU;
        Particle p = new Particle(new Vector3D(r0, 0, 0), new Vector3D(0, 0, 0), 1.0e20, 0.0, 3000.0);

        double dt = SimulationConfig.DT;
        for (int i = 0; i < 50; i++) {
            stepUnderStarGravity(p, dt);
        }

        double distanceAfter = p.getPosition().magnitude();
        assertTrue(distanceAfter < r0,
                "Una particella ferma deve avvicinarsi alla stella (caduta radiale), non allontanarsi");
    }
}
