package net.gommagomma.stardust;


import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.gravity.GravityModel;


public final class SimulationConfig
{
    private SimulationConfig() {}
    public static final String SESSION_ID = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

    // Phisical constants
    public static final double G = 6.674e-11;       // Costante di gravitazione universale (N*m^2/kg^2)
    public static final double K_COULOMB = 8.99e9;  // Costante di Coulomb (N*m^2/C^2)

    // Simulation parameters
    public static final int N = 15000;                      // Numero particelle
    public static final double DT = 3600.0;                   // Step temporale (secondi)
    public static final double SOFTENING = 1; //500.0;         // Softening parameter (m)
    public static final GravityModel ACTIVE_GRAVITY_MODEL = GravityModel.NEWTONIAN_CLAMPED;

    // Parametri Astronomici
    public static final double AU = 1.496e11;
    public static final double STAR_MASS = 1.989e30;       // Massa stella (sole) centrale (kg)
    public static final double STAR_RADIUS = 6.963e8;          //
    public static final double STAR_DENSITY = 1408.0;     // Densità media solare (kg/m³)
    public static final double DISK_INNER_RADIUS = 0.3 * AU;  // Raggio interno disco (m)
    public static final double DISK_OUTER_RADIUS = 0.7 * AU;         // Raggio esterno disco (m)
    public static final double V_REF_STAR = Math.sqrt((G * STAR_MASS) / DISK_INNER_RADIUS);
    public static final Particle STAR = new Particle(new Vector3D(0, 0, 0), new Vector3D(0, 0, 0), STAR_MASS, 0.0, STAR_DENSITY);

    // Aerodinamica e Gas Drag
    public static final double GAS_DENSITY_BASE = 1.4e-9;      // Densità gas a 1 AU (kg/m^3)
    public static final double GAS_PROFILE_EXPONENT = -2; // profilo di densità radiale del gas
    public static final double GAS_SUB_KEPLERIAN_FACTOR = 0.95; // Vel. gas rispetto al kepleriano
    
    // Perturbazione e Accrescimento
    public static final double FRAGMENTATION_MULTIPLIER = 2.0; // Deve restare > 1.0 (altrimenti la zona di rimbalzo sparisce)
    public static final double INITIAL_VELOCITY_DISPERSION = 0.005; // Eccita le eccentricita' e le inclinazioni permettendo agli anelli di incrociarsi.
    public static final double DUST_COHESION_THRESHOLD = 2.5; //
    public static final double GRAVITATIONAL_CAPTURE_MULTIPLIER = 1.0; // Moltiplicatore della v_esc mutua per la soglia di accrescimento
    public static final double HILL_AMPLIFICATION = 1.0;
    public static final double HILL_CAPTURE_FRACTION = 0.20; // frazione del raggio di cattura

    // Proprietà della Materia
    public static final double MASS_POWER_LAW_INDEX = 1.2; // distribuzione a potenza delle masse
    public static final double INITIAL_DUST_DENSITY = 100.0;
    public static final double BASE_PARTICLE_MASS_MIN = 2e19;
    public static final double BASE_PARTICLE_MASS_MAX = 2e21;
    public static final boolean ENABLE_ELECTROSTATIC_FORCE = false;
    public static final double MAX_INITIAL_CHARGE = 1e-9; // Carica massima iniziale (Coulomb)

    // Rendering
    public static final int FPS = 15;
    public static final int TOP_ORBITS_COUNT = 3; // Numero di orbite principali da evidenziare per massa del corpo
    public static final double GAP_MIN_CLEARING_RATIO = 0.7; // Percentuale minima di svuotamento radiale per evidenziare le zone a bassa densità
    public static final double DENSITY_RING_WIDTH = 0.001 * AU; // Larghezza dell'anello per l'analisi della densità (metri)

    // Logging
    public static final boolean LOG_ACCRETION_EVENTS = true;
    public static final boolean LOG_BOUNCE_EVENTS = true;
    public static final int LOG_SUMMARY_EVERY_N_STEPS = 100;

    // Savepoint (salva/riprendi la simulazione tra sessioni diverse)
    public static final String SAVEPOINT_FILE = "savepoint.txt";
    public static final int AUTOSAVE_INTERVAL_SECONDS = 300; // ogni 5 minuti (0 = disattivo)

    // Performance
    public static final boolean USE_PARALLEL_FORCES = Runtime.getRuntime().availableProcessors() > 1;
    public static final boolean USE_BARNES_HUT = true;  // approssima gravita'+Coulomb in O(N log N) invece di O(N^2)
    public static final double BARNES_HUT_THETA = 0.7;  // angolo di apertura: piu' basso = piu' preciso ma piu' lento
    public static final int BARNES_HUT_THRESHOLD = 2000; // al di sotto torna al calcolo parallelo
}