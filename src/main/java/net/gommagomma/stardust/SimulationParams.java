package net.gommagomma.stardust;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import net.gommagomma.stardust.io.SimulationPaths;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.gravity.GravityModel;

public class SimulationParams
{
	// Stella centrale
	public double centralStarMass = 1.989e30;
    public double centralStarRadius = 6.963e8;
    public double centralStarDensity = 1408.0;
    public Particle centralStar;

	// Disco iniziale
    public int n;
    public double initialParticleMassMin;
    public double initialParticleMassMax;
    public double diskInnerRadius;
    public double diskOuterRadius;
    public double initialVelocityDispersion;
    public double initialParticleDensity;
    public double initialMaxCharge;
    public double massPowerLawIndex;

    // Gravità
    public double dt;
    public double softening;
    public boolean useParallelForces;
    public boolean useBarnesHut;
    public double barnesHutTheta;
    public int barnesHutThreshold;
    public GravityModel activeGravityModel = GravityModel.NEWTONIAN_CLAMPED;
    public boolean enableElectrostaticForce;

    // Timestep adattivo (basato sul Courant preventivo)
    public double courantSafetyThreshold = 0.5; // sopra questa soglia, dt viene ridotto per lo step corrente
    public double minDtFraction = 0.05;         // pavimento: dt non scende mai sotto questa frazione del valore nominale

    // Collisioni
    public double hillCaptureFraction;
    public double hillAmplification;
    public double gravitationalCaptureMultiplier;
    public double mergeVelocityFloor;
    public double fragmentationMultiplier;

    // Drag / Gas
    public double dragReferenceDensity;
    public double gasDensityBase;
    public double gasProfileExponent;

    // Rendering / Diagnostica
    public double densityRingWidth;
    public int topOrbitsCount;
    public double gapMinClearingRatio;

    // Sessione / I/O
    public int logSummaryEveryNSteps;
    public int screenshotEveryNSteps;
    public int fps;
    public int autosaveInterval;


    public SimulationParams(SimulationPaths paths) {
    	load(paths.defaultParamsFile.toFile());
    	load(paths.paramsFile.toFile());

    	centralStar = new Particle(new Vector3D(0, 0, 0), new Vector3D(0, 0, 0), centralStarMass, 0.0, centralStarDensity);
    }

    /**
     * Costruttore "vuoto": non tocca il filesystem, non duplica alcun valore di default -- i campi
     * restano ai valori grezzi del linguaggio (0 / false), a parte quelli con un inizializzatore di
     * campo esplicito qui sopra (centralStarMass, centralStarRadius, centralStarDensity). Pensato
     * per essere usato SOLO tramite una classe di supporto lato test (es. TestParams.defaults())
     * che popola esplicitamente i valori che servono -- non per essere usato direttamente senza
     * impostare i campi necessari, altrimenti si ottiene una simulazione degenere (dt=0, ecc.).
     */
    public SimulationParams() {
        centralStar = new Particle(new Vector3D(0, 0, 0), new Vector3D(0, 0, 0), centralStarMass, 0.0, centralStarDensity);
    }

    public void load(File file)
    {
        if (file == null || !file.exists()) return;

        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.split("#", 2)[0].trim();
                if (line.isEmpty()) continue;

                int eq = line.indexOf('=');
                if (eq < 0) continue;

                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                apply(key, value);
            }
        } catch (IOException e) {
            System.err.println("Errore lettura parametri da " + file + " (" + e.getMessage() + "), valori precedenti mantenuti.");
        }
    }

    private void apply(String key, String value)
    {
        switch (key) {
            // --- Stella centrale ---
            case "centralStarMass":                centralStarMass = Double.parseDouble(value); break;
            case "centralStarRadius":               centralStarRadius = Double.parseDouble(value); break;
            case "centralStarDensity":              centralStarDensity = Double.parseDouble(value); break;

            // --- Disco iniziale ---
            case "n":                              n = Integer.parseInt(value); break;
            case "initialParticleMassMin":         initialParticleMassMin = Double.parseDouble(value); break;
            case "initialParticleMassMax":         initialParticleMassMax = Double.parseDouble(value); break;
            case "diskInnerRadiusAU":              diskInnerRadius = Double.parseDouble(value)*PhysicsConstants.AU; break;
            case "diskOuterRadiusAU":              diskOuterRadius = Double.parseDouble(value)*PhysicsConstants.AU; break;
            case "initialVelocityDispersion":      initialVelocityDispersion = Double.parseDouble(value); break;
            case "initialParticleDensity":         initialParticleDensity = Double.parseDouble(value); break;
            case "initialMaxCharge":               initialMaxCharge = Double.parseDouble(value); break;
            case "massPowerLawIndex":              massPowerLawIndex = Double.parseDouble(value); break;

            // --- Gravità ---
            case "dt":                             dt = Double.parseDouble(value); break;
            case "softening":                      softening = Double.parseDouble(value); break;
            case "useParallelForces":              useParallelForces = Boolean.parseBoolean(value); break;
            case "useBarnesHut":                   useBarnesHut = Boolean.parseBoolean(value); break;
            case "barnesHutTheta":                 barnesHutTheta = Double.parseDouble(value); break;
            case "barnesHutThreshold":             barnesHutThreshold = Integer.parseInt(value); break;
            case "activeGravityModel":             activeGravityModel = GravityModel.valueOf(value); break;
            case "enableElectrostaticForce":       enableElectrostaticForce = Boolean.parseBoolean(value); break;

            // --- Collisioni ---
            case "hillCaptureFraction":             hillCaptureFraction = Double.parseDouble(value); break;
            case "hillAmplification":               hillAmplification = Double.parseDouble(value); break;
            case "gravitationalCaptureMultiplier":  gravitationalCaptureMultiplier = Double.parseDouble(value); break;
            case "mergeVelocityFloor":              mergeVelocityFloor = Double.parseDouble(value); break;
            case "fragmentationMultiplier":         fragmentationMultiplier = Double.parseDouble(value); break;
            case "courantSafetyThreshold":          courantSafetyThreshold = Double.parseDouble(value); break;
            case "minDtFraction":                   minDtFraction = Double.parseDouble(value); break;

            // --- Drag / Gas ---
            case "dragReferenceDensity":            dragReferenceDensity = Double.parseDouble(value); break;
            case "gasDensityBase":                  gasDensityBase = Double.parseDouble(value); break;
            case "gasProfileExponent":              gasProfileExponent = Double.parseDouble(value); break;

            // --- Rendering / Diagnostica ---
            case "densityRingWidthAU":              densityRingWidth = Double.parseDouble(value)*PhysicsConstants.AU; break;
            case "topOrbitsCount":                  topOrbitsCount = Integer.parseInt(value); break;
            case "gapMinClearingRatio":             gapMinClearingRatio = Double.parseDouble(value); break;

            // --- Sessione / I/O ---
            case "logSummaryEveryNSteps":           logSummaryEveryNSteps = Integer.parseInt(value); break;
            case "screenshotEveryNSteps":           screenshotEveryNSteps = Integer.parseInt(value); break;
            case "fps":                             fps = Integer.parseInt(value); break;
            case "autosaveInterval":                autosaveInterval = Integer.parseInt(value); break;

            default:
                System.err.println("Parametro sconosciuto ignorato: " + key);
        }
    }
}