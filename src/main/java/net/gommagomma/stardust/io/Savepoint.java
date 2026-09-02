package net.gommagomma.stardust.io;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import net.gommagomma.stardust.SimulationEngine;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Salva/ripristina l'intero stato della simulazione (particelle + contatori + tempo) su un file di testo,
 * per poter interrompere una run lunga e riprenderla in una sessione successiva.
 */
public class Savepoint
{
    private static final String MAGIC = "STARDUST_SAVEPOINT_V1";

    /**
     * Verifica se esiste il savepoint indicato nel path.
     * @param path file di savepoint da verificare
     * @return true se il file esiste
     */
    public static boolean exists(String path) {
        return Files.exists(Paths.get(path));
    }

    /**
     * Scrive un savepoint su file.
     * Tiene il lock sull'intera lista particelle per tutta la durata della scrittura.
     * 
     * @param path file di savepoint da salvare
     * @param engine Il motore di simulazione fisica da cui leggere lo stato
     * @throws IOException
     */
    public static void save(String path, SimulationEngine engine) throws IOException
    {
        List<Particle> snapshot;
        double simTime;
        long stepCount, totalMerges, totalBounces, totalFragmentations, totalEscapes, totalStarFalls;

        // 1. SNAPSHOT VELOCISSIMO (Lock ridotto al minimo)
        synchronized (engine.getParticles())
        {
            snapshot = new ArrayList<>(engine.getParticles());
            simTime = engine.getSimulationTime();
            stepCount = engine.getStepCount();
            totalMerges = engine.getTotalMerges();
            totalBounces = engine.getTotalBounces();
            totalFragmentations = engine.getTotalFragmentations();
            totalEscapes = engine.getTotalEscapes();
            totalStarFalls = engine.getTotalStarFalls();
        }

        // 2. SCRITTURA SU DISCO IN CORRENTE CONTINUA (Fuori dal lock!)
        java.nio.file.Path outputPath = java.nio.file.Paths.get(path);
        if (outputPath.getParent() != null) {
            java.nio.file.Files.createDirectories(outputPath.getParent());
        }

        try (BufferedWriter w = java.nio.file.Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))
        {
            w.write(MAGIC); w.newLine();
            w.write("simulationTime=" + simTime); w.newLine();
            w.write("stepCount=" + stepCount); w.newLine();
            w.write("totalMerges=" + totalMerges); w.newLine();
            w.write("totalBounces=" + totalBounces); w.newLine();
            w.write("totalFragmentations=" + totalFragmentations); w.newLine();
            w.write("totalEscapes=" + totalEscapes); w.newLine();
            w.write("totalStarFalls=" + totalStarFalls); w.newLine();
            w.write("particleCount=" + snapshot.size()); w.newLine();
            w.newLine();
            w.write("PARTICLES:"); w.newLine();

            StringBuilder sb = new StringBuilder(128);
            for (Particle p : snapshot)
            {
                if (!p.isAlive()) continue; // Evita di salvare particelle morte residue

                Vector3D pos = p.getPosition();
                Vector3D vel = p.getVelocity();
                sb.setLength(0);
                sb.append(p.getId()).append(',')
                  .append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(',')
                  .append(vel.getX()).append(',').append(vel.getY()).append(',').append(vel.getZ()).append(',')
                  .append(p.getMass()).append(',')
                  .append(p.getCharge()).append(',')
                  .append(p.getDensity()).append(',')
                  .append(p.getInitialRadius()).append(',')
                  .append(p.getMergerCount());
                w.write(sb.toString());
                w.newLine();
            }
            w.flush();
        }

        System.out.printf("[SAVEPOINT] Salvato: %s (%d particelle, t=%.1fs, %d fusioni, %d frammentazioni)%n", 
                path, snapshot.size(), simTime, totalMerges, totalFragmentations);
    }

    /**
     * Legge un savepoint da file.
     * 
     * @param path file di savepoint da ripristinare
     * @return il savepoint da ripristinare
     * @throws IOException
     */
    public static SavepointState load(String path)
    throws IOException
    {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8)))
        {
            String magic = r.readLine();
            if (magic == null || !magic.equals(MAGIC)) {
                throw new IOException("File di savepoint non riconosciuto o corrotto: " + path);
            }

            double simulationTime = 0;
            long stepCount = 0, totalMerges = 0, totalBounces = 0, totalFragmentations = 0, totalEscapes = 0, totalStarFalls = 0;

            String line;
            while ((line = r.readLine()) != null) {
                if (line.equals("PARTICLES:")) break;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq);
                String value = line.substring(eq + 1);
                switch (key) {
                    case "simulationTime":      simulationTime = Double.parseDouble(value); break;
                    case "stepCount":           stepCount = Long.parseLong(value); break;
                    case "totalMerges":         totalMerges = Long.parseLong(value); break;
                    case "totalBounces":        totalBounces = Long.parseLong(value); break;
                    case "totalFragmentations": totalFragmentations = Long.parseLong(value); break;
                    case "totalEscapes":        totalEscapes = Long.parseLong(value); break;
                    case "totalStarFalls":      totalStarFalls = Long.parseLong(value); break;
                    default: break;
                }
            }

            List<Particle> particles = new ArrayList<>();
            int maxId = -1;

            while ((line = r.readLine()) != null)
            {
                if (line.isEmpty()) continue;
                String[] f = line.split(",");
                int id = Integer.parseInt(f[0]);
                Vector3D pos = new Vector3D(Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]));
                Vector3D vel = new Vector3D(Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]));
                double mass = Double.parseDouble(f[7]);
                double charge = Double.parseDouble(f[8]);
                double density = Double.parseDouble(f[9]);
                double initialRadius = Double.parseDouble(f[10]);
                int mergerCount = Integer.parseInt(f[11]);

                particles.add(new Particle(id, pos, vel, mass, charge, density, initialRadius, mergerCount));
                if (id > maxId) maxId = id;
            }

            // Evita che eventuali NUOVE particelle create dopo il ripristino riusino ID gia' presenti
            Particle.ensureIdCounterAtLeast(maxId + 1);

            System.out.printf("[SAVEPOINT] Ripristinato: %s (%d particelle, t=%.1fs, %d fusioni, %d frammentazioni)%n", 
                    path, particles.size(), simulationTime, totalMerges, totalFragmentations);

            return new SavepointState(particles, simulationTime, stepCount, totalMerges, totalBounces, totalFragmentations, totalEscapes, totalStarFalls);
        }
    }

    // Contenitore semplice per i dati di un savepoint
    public static class SavepointState
    {
        public final List<Particle> particles;
        public final double simulationTime;
        public final long stepCount;
        public final long totalMerges;
        public final long totalBounces;
        public final long totalFragmentations;
        public final long totalEscapes;
        public final long totalStarFalls;

        SavepointState(List<Particle> particles, double simulationTime, long stepCount, 
                       long totalMerges, long totalBounces, long totalFragmentations, 
                       long totalEscapes, long totalStarFalls)
        {
            this.particles = particles;
            this.simulationTime = simulationTime;
            this.stepCount = stepCount;
            this.totalMerges = totalMerges;
            this.totalBounces = totalBounces;
            this.totalFragmentations = totalFragmentations;
            this.totalEscapes = totalEscapes;
            this.totalStarFalls = totalStarFalls;
        }
    }
}