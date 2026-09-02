package net.gommagomma.stardust.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import net.gommagomma.stardust.SimulationConfig;
import net.gommagomma.stardust.model.Particle;

public class SimulationPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    // Raggio di riferimento fisso per una particella di massa base (minima)
    private static final double MIN_PARTICLE_RADIUS = Math.cbrt((3.0 * SimulationConfig.BASE_PARTICLE_MASS_MIN)
            / (4.0 * Math.PI * SimulationConfig.INITIAL_DUST_DENSITY));

    private double simulatedTimeSeconds = 0.0;
    private double currentDtSeconds = 0.0;

    // Array di stato per il rendering
    private int[] renderId = new int[0];
    private float[] renderX = new float[0];
    private float[] renderY = new float[0];
    private float[] renderVx = new float[0];
    private float[] renderVy = new float[0];
    private float[] renderRadius = new float[0];
    private double[] renderMass = new double[0];
    private boolean[] renderIsMerged = new boolean[0];
    private int particleCount = 0;

    // ID univoco della particella selezionata tramite mouse (-1 = nessuna)
    private int selectedParticleId = -1;
    // Flag per il lock della telecamera sulla particella selezionata
    private boolean isCameraLocked = false;

    // Gestione Zoom & Pan
    private double zoomFactor = 1.0;
    private double panX = 0.0;
    private double panY = 0.0;

    private static final double MIN_ZOOM = 0.2;
    private static final double MAX_ZOOM = 50.0;
    private static final double ZOOM_SENSITIVITY = 1.1;

    public SimulationPanel() {
        this.setBackground(Color.BLACK);

        // --- INTERAZIONI MOUSE: CLICK SELEZIONE & PANNING ---
        MouseAdapter mouseHandler = new MouseAdapter() {
            private Point lastPt;

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (e.getClickCount() == 2) {
                        resetView();
                    } else {
                        // Passiamo la presenza del tasto CTRL premuto alla funzione di gestione click
                        handleMouseClick(e.getX(), e.getY(), e.isControlDown());
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                    lastPt = e.getPoint();
                    // Il pan manuale sblocca la fotocamera
                    isCameraLocked = false;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (lastPt != null && (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e))) {
                    panX += (e.getX() - lastPt.x);
                    panY += (e.getY() - lastPt.y);
                    lastPt = e.getPoint();
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                lastPt = null;
            }
        };

        this.addMouseListener(mouseHandler);
        this.addMouseMotionListener(mouseHandler);

        // --- INTERAZIONE MOUSE: ZOOM ---
        this.addMouseWheelListener(e -> {
            double zoomMultiplier = (e.getWheelRotation() < 0) ? ZOOM_SENSITIVITY : (1.0 / ZOOM_SENSITIVITY);
            double newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoomFactor * zoomMultiplier));
            double actualFactor = newZoom / zoomFactor;

            if (!isCameraLocked) {
                Point mousePt = e.getPoint();
                int panelCenterX = getWidth() / 2;
                int panelCenterY = getHeight() / 2;

                panX = (panX - (mousePt.x - panelCenterX)) * actualFactor + (mousePt.x - panelCenterX);
                panY = (panY - (mousePt.y - panelCenterY)) * actualFactor + (mousePt.y - panelCenterY);
            }

            zoomFactor = newZoom;
            repaint();
        });
    }

    /**
     * Ripristina la vista di default (zoom, posizione e sblocco telecamera).
     */
    public void resetView() {
        this.zoomFactor = 1.0;
        this.panX = 0.0;
        this.panY = 0.0;
        this.isCameraLocked = false;
        repaint();
    }

    private void handleMouseClick(int mouseX, int mouseY, boolean isCtrlPressed) {
        synchronized (this) {
            if (particleCount == 0) return;

            int centerX = (int) (getWidth() / 2.0 + panX);
            int centerY = (int) (getHeight() / 2.0 + panY);

            double maxExpectedRadius = SimulationConfig.DISK_OUTER_RADIUS;
            double maxWindowRadius = Math.min(getWidth() / 2.0, getHeight() / 2.0) * 0.85;
            double baseScale = maxWindowRadius / maxExpectedRadius;
            double scale = baseScale * zoomFactor;

            int closestIdx = -1;
            double minDistSq = Double.MAX_VALUE;

            for (int i = 0; i < particleCount; i++) {
                int px = centerX + (int) (renderX[i] * scale);
                int py = centerY + (int) (renderY[i] * scale);

                double dx = px - mouseX;
                double dy = py - mouseY;
                double distSq = dx * dx + dy * dy;

                double radiusRatio = Math.max(1.0, renderRadius[i] / MIN_PARTICLE_RADIUS);
                double particleSizePx = renderIsMerged[i] ?
                        Math.min(24, (3 + Math.log(radiusRatio) * 2.0) * Math.sqrt(zoomFactor)) :
                        Math.min(16, (1.5 + Math.log(radiusRatio) * 1.5) * Math.sqrt(zoomFactor));
                
                double hitTolerance = Math.max(12.0, particleSizePx / 2.0 + 4.0);
                double allowedDistSq = hitTolerance * hitTolerance;

                if (distSq < allowedDistSq && distSq < minDistSq) {
                    minDistSq = distSq;
                    closestIdx = i;
                }
            }

            // Gestione selezione e Lock con CTRL
            if (closestIdx == -1) {
                selectedParticleId = -1;
                isCameraLocked = false;
            } else {
                int clickedId = renderId[closestIdx];
                
                if (clickedId == selectedParticleId) {
                    // Se la particella era già selezionata:
                    if (isCtrlPressed) {
                        // CTRL+Click toggle lo stato del lock
                        isCameraLocked = !isCameraLocked;
                    } else {
                        // Click semplice su particella già selezionata -> deseleziona tutto
                        selectedParticleId = -1;
                        isCameraLocked = false;
                    }
                } else {
                    // Nuova particella selezionata:
                    selectedParticleId = clickedId;
                    // Il lock si attiva SOLO SE CTRL è premuto durante il click
                    isCameraLocked = isCtrlPressed;
                }
            }
        }
        repaint();
    }

    // Metodo THREAD-SAFE per aggiornare lo stato da disegnare
    public synchronized void updateSnapshot(List<Particle> particles, double totalTime, double dt) {
        this.simulatedTimeSeconds = totalTime;
        this.currentDtSeconds = dt;

        int size = particles.size();
        if (renderX.length < size) {
            renderId = new int[size];
            renderX = new float[size];
            renderY = new float[size];
            renderVx = new float[size];
            renderVy = new float[size];
            renderRadius = new float[size];
            renderMass = new double[size];
            renderIsMerged = new boolean[size];
        }

        if (size < particleCount) {
            Arrays.fill(renderMass, size, particleCount, 0.0);
        }

        particleCount = size;
        for (int i = 0; i < size; i++) {
            Particle p = particles.get(i);
            renderId[i] = p.getId();
            renderX[i] = (float) p.getPosition().getX();
            renderY[i] = (float) p.getPosition().getY();
            renderVx[i] = (float) p.getVelocity().getX();
            renderVy[i] = (float) p.getVelocity().getY();
            renderRadius[i] = (float) p.getRadius();
            renderMass[i] = p.getMass();
            renderIsMerged[i] = p.isAggregated();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int[] ids;
        float[] x;
        float[] y;
        float[] vx;
        float[] vy;
        float[] radius;
        double[] mass;
        boolean[] merged;
        int count;
        int targetId;

        synchronized (this) {
            ids = renderId;
            x = renderX;
            y = renderY;
            vx = renderVx;
            vy = renderVy;
            radius = renderRadius;
            mass = renderMass;
            merged = renderIsMerged;
            count = particleCount;
            targetId = selectedParticleId;
        }

        if (count == 0) return;

        int selectedIdx = -1;
        if (targetId != -1) {
            for (int i = 0; i < count; i++) {
                if (ids[i] == targetId) {
                    selectedIdx = i;
                    break;
                }
            }
            if (selectedIdx == -1) {
                isCameraLocked = false;
                selectedParticleId = -1;
            }
        }

        double maxExpectedRadius = SimulationConfig.DISK_OUTER_RADIUS;
        double maxWindowRadius = Math.min(getWidth() / 2.0, getHeight() / 2.0) * 0.85;
        double baseScale = maxWindowRadius / maxExpectedRadius;
        double scale = baseScale * zoomFactor;

        // Inseguimento della camera se attiva
        if (isCameraLocked && selectedIdx != -1) {
            panX = -x[selectedIdx] * scale;
            panY = -y[selectedIdx] * scale;
        }

        int centerX = (int) (getWidth() / 2.0 + panX);
        int centerY = (int) (getHeight() / 2.0 + panY);

        // 1. Stella Centrale
        g2.setColor(new Color(255, 180, 50));
        int starRadiusPx = (int) Math.max(4.0, 6.0 * Math.sqrt(zoomFactor));
        g2.fillOval(centerX - starRadiusPx, centerY - starRadiusPx, starRadiusPx * 2, starRadiusPx * 2);

        // 1b. Gap radiali
        drawLowDensityRadialGaps(g2, x, y, count, scale, centerX, centerY);

        // 2. Top N corpi
        int[] topIndices = findTopMassiveIndices(mass, count, SimulationConfig.TOP_ORBITS_COUNT);

        // 3. Orbite
        double mu = SimulationConfig.G * SimulationConfig.STAR_MASS;
        Color defaultOrbitColor = new Color(255, 255, 255, 70);
        BasicStroke defaultStroke = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                                10.0f, new float[]{5.0f, 5.0f}, 0.0f);

        for (int idx : topIndices) {
            if (idx != -1 && mass[idx] > 0) {
                drawKeplerianOrbit(g2, x[idx], y[idx], vx[idx], vy[idx], mu, scale, centerX, centerY, defaultOrbitColor, defaultStroke);

                int px = centerX + (int) (x[idx] * scale);
                int py = centerY + (int) (y[idx] * scale);
                int crosshairSize = (int) Math.max(12, 12 * Math.sqrt(zoomFactor));
                g2.setColor(new Color(255, 255, 255, 200));
                g2.drawOval(px - crosshairSize / 2, py - crosshairSize / 2, crosshairSize, crosshairSize);
            }
        }

        if (selectedIdx != -1) {
            Color selectedOrbitColor = new Color(255, 215, 0, 220);
            BasicStroke selectedStroke = new BasicStroke(1.5f);
            drawKeplerianOrbit(g2, x[selectedIdx], y[selectedIdx], vx[selectedIdx], vy[selectedIdx], mu, scale, centerX, centerY, selectedOrbitColor, selectedStroke);
        }

        // 4. Disegno particelle
        for (int i = 0; i < count; i++) {
            int px = centerX + (int) (x[i] * scale);
            int py = centerY + (int) (y[i] * scale);

            if (px < 0 || px >= getWidth() || py < 0 || py >= getHeight()) {
                continue;
            }

            double r = radius[i];

            if (merged[i]) {
                double radiusRatio = Math.max(1.0, r / MIN_PARTICLE_RADIUS);
                int sizePx = (int) Math.min(24, (3 + Math.log(radiusRatio) * 2.0) * Math.sqrt(zoomFactor));
                sizePx = Math.max(3, sizePx);

                double logRatio = 3.0 * Math.log10(radiusRatio);
                double maxLog = 3.5;
                float factor = (float) Math.min(1.0, Math.max(0.0, logRatio / maxLog));

                float red   = 0.65f + (0.35f * factor);
                float green = 0.30f + (0.60f * factor);
                float blue  = 0.15f + (0.35f * factor);
                float alpha = 0.50f + (0.45f * factor);

                g2.setColor(new Color(red, green, blue, alpha));
                g2.fillOval(px - sizePx / 2, py - sizePx / 2, sizePx, sizePx);

            } else {
                double radiusRatio = Math.max(1.0, r / MIN_PARTICLE_RADIUS);
                int sizePx = (int) Math.min(16, (1.5 + Math.log(radiusRatio) * 1.5) * Math.sqrt(zoomFactor));
                sizePx = Math.max(1, sizePx);

                double logRatio = 2.5 * Math.log10(radiusRatio);
                double maxLog = 3.5;
                float factor = (float) Math.min(1.0, Math.max(0.0, logRatio / maxLog));

                float red   = 0.15f + (0.25f * factor);
                float green = 0.50f + (0.45f * factor);
                float blue  = 0.85f + (0.15f * factor);
                float alpha = 0.30f + (0.50f * factor);

                g2.setColor(new Color(red, green, blue, alpha));

                if (sizePx <= 1) {
                    g2.fillRect(px, py, 1, 1);
                } else {
                    g2.fillOval(px - sizePx / 2, py - sizePx / 2, sizePx, sizePx);
                }
            }

            // Mirino HUD
            if (i == selectedIdx) {
                g2.setColor(Color.YELLOW);
                g2.setStroke(new BasicStroke(1.2f));
                int rPx = (int) Math.max(8, 10 * Math.sqrt(zoomFactor));

                g2.drawOval(px - rPx, py - rPx, rPx * 2, rPx * 2);

                int len = 4;
                g2.drawLine(px - rPx - len, py, px - rPx + 2, py);
                g2.drawLine(px + rPx - 2, py, px + rPx + len, py);
                g2.drawLine(px, py - rPx - len, px, py - rPx + 2);
                g2.drawLine(px, py + rPx - 2, px, py + rPx + len);
            }
        }

        // 5. HUD Selezione
        if (selectedIdx != -1) {
            drawSelectionHUD(g2, ids[selectedIdx], x[selectedIdx], y[selectedIdx],
                             vx[selectedIdx], vy[selectedIdx],
                             mass[selectedIdx], radius[selectedIdx], merged[selectedIdx]);
        }

        drawTimeHUD(g2, simulatedTimeSeconds, currentDtSeconds);
    }

    private void drawSelectionHUD(Graphics2D g2, int id, float rx, float ry,
                                  float vx, float vy, double m, float r, boolean isMerged) {
        int hudX = 15;
        int hudY = 15;
        int hudWidth = 220;
        int hudHeight = 115;

        g2.setColor(new Color(10, 15, 30, 200));
        g2.fillRoundRect(hudX, hudY, hudWidth, hudHeight, 10, 10);

        g2.setColor(new Color(255, 215, 0, 180));
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRoundRect(hudX, hudY, hudWidth, hudHeight, 10, 10);

        g2.setColor(Color.WHITE);
        int textX = hudX + 12;
        int textY = hudY + 20;
        int lineHeight = 17;

        double rAU = Math.hypot(rx, ry) / SimulationConfig.AU;
        double vKmS = Math.hypot(vx, vy) / 1000.0;
        double radiusKm = r / 1000.0;

        g2.setColor(Color.YELLOW);
        String lockStatus = isCameraLocked ? " [LOCKED]" : "";
        g2.drawString(String.format("CORPO ID #%d (%s)%s", id, isMerged ? "Accresciuto" : "Base", lockStatus), textX, textY);

        g2.setColor(Color.WHITE);
        g2.drawString(String.format("Massa: %.3e kg", m), textX, textY + lineHeight);
        g2.drawString(String.format("Raggio: %.1f km", radiusKm), textX, textY + lineHeight * 2);
        g2.drawString(String.format("Distanza R: %.4f AU", rAU), textX, textY + lineHeight * 3);
        g2.drawString(String.format("Velocità V: %.2f km/s", vKmS), textX, textY + lineHeight * 4);
    }

    private int[] findTopMassiveIndices(double[] mass, int count, int topN) {
        int[] top = new int[topN];
        Arrays.fill(top, -1);

        for (int i = 0; i < count; i++) {
            double m = mass[i];
            if (m <= 0) continue;

            if (top[0] == -1 || m > mass[top[0]]) {
                top[2] = top[1];
                top[1] = top[0];
                top[0] = i;
            } else if (top[1] == -1 || m > mass[top[1]]) {
                top[2] = top[1];
                top[1] = i;
            } else if (top[2] == -1 || m > mass[top[2]]) {
                top[2] = i;
            }
        }
        return top;
    }

    private void drawKeplerianOrbit(Graphics2D g2, double rx, double ry, double vx, double vy,
                                    double mu, double scale, int centerX, int centerY,
                                    Color orbitColor, BasicStroke stroke) {
        double rMag = Math.hypot(rx, ry);
        double vMag = Math.hypot(vx, vy);
        if (rMag == 0 || vMag == 0) return;

        double energy = (vMag * vMag / 2.0) - (mu / rMag);
        if (energy >= 0) return;

        double a = -mu / (2.0 * energy);

        double rDotV = rx * vx + ry * vy;
        double ex = ((vMag * vMag - mu / rMag) * rx - rDotV * vx) / mu;
        double ey = ((vMag * vMag - mu / rMag) * ry - rDotV * vy) / mu;
        double e = Math.hypot(ex, ey);

        if (e >= 1.0) return;

        double b = a * Math.sqrt(1.0 - e * e);
        double c = a * e;

        double omega = Math.atan2(ey, ex);

        double cxWorld = -c * Math.cos(omega);
        double cyWorld = -c * Math.sin(omega);

        double aPx = a * scale;
        double bPx = b * scale;
        double cxPx = centerX + (cxWorld * scale);
        double cyPx = centerY + (cyWorld * scale);

        AffineTransform oldTransform = g2.getTransform();

        g2.translate(cxPx, cyPx);
        g2.rotate(omega);

        g2.setColor(orbitColor);
        g2.setStroke(stroke);

        g2.draw(new Ellipse2D.Double(-aPx, -bPx, 2.0 * aPx, 2.0 * bPx));

        g2.setTransform(oldTransform);
    }

    private void drawLowDensityRadialGaps(Graphics2D g2, float[] x, float[] y, int count,
                                          double scale, int centerX, int centerY) {
        double maxRadius = SimulationConfig.DISK_OUTER_RADIUS;
        double minRadius = SimulationConfig.DISK_INNER_RADIUS;
        double binWidthWorld = SimulationConfig.DENSITY_RING_WIDTH;

        int numBins = (int) Math.ceil(maxRadius / binWidthWorld);
        if (numBins < 5) return;

        int[] binCounts = new int[numBins];

        for (int i = 0; i < count; i++) {
            double r = Math.hypot(x[i], y[i]);
            int binIndex = (int) (r / binWidthWorld);
            if (binIndex >= 0 && binIndex < numBins) {
                binCounts[binIndex]++;
            }
        }

        int startBin = Math.max(2, (int) (minRadius / binWidthWorld));

        for (int b = startBin; b < numBins - 2; b++) {
            double rInnerWorld = b * binWidthWorld;

            if (rInnerWorld < minRadius) {
                continue;
            }

            double localAvg = (binCounts[b - 2] + binCounts[b - 1] + binCounts[b + 1] + binCounts[b + 2]) / 4.0;
            if (localAvg < 5.0) continue;

            double maxAllowedDensityRatio = 1.0 - SimulationConfig.GAP_MIN_CLEARING_RATIO;
            double lowDensityThreshold = localAvg * maxAllowedDensityRatio;

            if (binCounts[b] < lowDensityThreshold) {
                double rOuterWorld = (b + 1) * binWidthWorld;

                double rInnerPx = rInnerWorld * scale;
                double rOuterPx = rOuterWorld * scale;
                double thicknessPx = Math.max(2.0, rOuterPx - rInnerPx);

                double avgRadiusPx = (rInnerPx + rOuterPx) / 2.0;
                int drawDiameter = (int) (avgRadiusPx * 2.0);

                float depthFactor = (float) (1.0 - (binCounts[b] / lowDensityThreshold));
                int alpha = (int) (50 + depthFactor * 110);

                g2.setColor(new Color(255, 40, 80, alpha));
                g2.setStroke(new BasicStroke((float) thicknessPx));

                g2.drawOval(centerX - (drawDiameter / 2), centerY - (drawDiameter / 2), drawDiameter, drawDiameter);
            }
        }
    }

    private void drawTimeHUD(Graphics2D g2, double totalSimulatedSeconds, double currentDt) {
        int hudWidth = 210;
        int hudHeight = 65;
        int hudX = getWidth() - hudWidth - 15;
        int hudY = 15;

        g2.setColor(new Color(10, 15, 30, 200));
        g2.fillRoundRect(hudX, hudY, hudWidth, hudHeight, 10, 10);

        g2.setColor(new Color(0, 180, 220, 180));
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRoundRect(hudX, hudY, hudWidth, hudHeight, 10, 10);

        double days = totalSimulatedSeconds / (24.0 * 3600.0);
        double years = days / 365.25;

        g2.setColor(Color.WHITE);
        int textX = hudX + 12;
        int textY = hudY + 22;

        g2.drawString(String.format("Tempo: %.2f Anni", years), textX, textY);
        g2.drawString(String.format("           (%.1f giorni)", days), textX, textY + 16);

        g2.setColor(new Color(160, 200, 220));
        g2.drawString(String.format("dt: %.1f s/step", currentDt), textX, textY + 34);
    }

    public void saveScreenshot(File outputFile) {
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        BufferedImage image = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        this.printAll(g2);
        g2.dispose();

        try {
            ImageIO.write(image, "png", outputFile);
            System.out.printf("[SCREENSHOT] Salvato: %s%n", outputFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Errore durante il salvataggio dello screenshot: " + e.getMessage());
        }
    }
}