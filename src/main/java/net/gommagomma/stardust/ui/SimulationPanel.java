package net.gommagomma.stardust.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
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
import net.gommagomma.stardust.SimulationEngine;
import net.gommagomma.stardust.model.Particle;

public class SimulationPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private SimulationEngine simulationEngine;

    private static final double MIN_PARTICLE_RADIUS = Math.cbrt((3.0 * SimulationConfig.BASE_PARTICLE_MASS_MIN)
            / (4.0 * Math.PI * SimulationConfig.INITIAL_DUST_DENSITY));

    private double simulatedTimeSeconds = 0.0;
    private double currentDtSeconds = 0.0;

    private int[] renderId = new int[0];
    private float[] renderX = new float[0];
    private float[] renderY = new float[0];
    private float[] renderVx = new float[0];
    private float[] renderVy = new float[0];
    private float[] renderRadius = new float[0];
    private double[] renderMass = new double[0];
    private boolean[] renderIsMerged = new boolean[0];
    private int particleCount = 0;

    private int selectedParticleId = -1;
    private boolean isCameraLocked = false;
    private boolean isCoRotatingViewActive = false;

    private int activeGapParticleId = -1;

    private boolean isPaused = false;

    private double zoomFactor = 1.0;
    private double panX = 0.0;
    private double panY = 0.0;

    private static final double MIN_ZOOM = 0.2;
    private static final double MAX_ZOOM = 50.0;
    private static final double ZOOM_SENSITIVITY = 1.1;

    public SimulationPanel() {
        this(null);
    }

    public SimulationPanel(SimulationEngine engine) {
        this.simulationEngine = engine;
        this.setBackground(Color.BLACK);
        this.setFocusable(true);

        this.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                char c = Character.toLowerCase(e.getKeyChar());
                if (c == 'p') {
                    togglePause();
                } else if (c == 'g') {
                    toggleGapForSelectedParticle();
                }
            }
        });

        MouseAdapter mouseHandler = new MouseAdapter() {
            private Point lastPt;

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (e.getClickCount() == 2) {
                        resetView();
                    } else {
                        handleMouseClick(e.getX(), e.getY(), e.isControlDown(), e.isShiftDown());
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                    lastPt = e.getPoint();
                    isCameraLocked = false;
                    isCoRotatingViewActive = false;
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

        this.addMouseWheelListener(e -> {
            double zoomMultiplier = (e.getWheelRotation() < 0) ? ZOOM_SENSITIVITY : (1.0 / ZOOM_SENSITIVITY);
            double newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoomFactor * zoomMultiplier));
            double actualFactor = newZoom / zoomFactor;

            if (!isCameraLocked && !isCoRotatingViewActive) {
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

    public void setSimulationEngine(SimulationEngine engine) {
        this.simulationEngine = engine;
    }

    public boolean isPaused() {
        return isPaused;
    }

    public void togglePause() {
        isPaused = !isPaused;
        if (simulationEngine != null) {
            simulationEngine.setPaused(isPaused);
        }
        repaint();
    }

    public void resetView() {
        this.zoomFactor = 1.0;
        this.panX = 0.0;
        this.panY = 0.0;
        this.isCameraLocked = false;
        this.isCoRotatingViewActive = false;
        this.selectedParticleId = -1;
        this.activeGapParticleId = -1;
        repaint();
    }

    private synchronized void toggleGapForSelectedParticle() {
        if (selectedParticleId != -1) {
            if (activeGapParticleId == selectedParticleId) {
                activeGapParticleId = -1;
            } else {
                activeGapParticleId = selectedParticleId;
            }
            repaint();
        }
    }

    private void handleMouseClick(int mouseX, int mouseY, boolean isCtrlPressed, boolean isShiftPressed) {
        this.requestFocusInWindow();
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

            if (closestIdx == -1) {
                selectedParticleId = -1;
                activeGapParticleId = -1;
                isCameraLocked = false;
                isCoRotatingViewActive = false;
            } else {
                int clickedId = renderId[closestIdx];
                if (isCtrlPressed && isShiftPressed) {
                    if (selectedParticleId == clickedId) {
                        isCoRotatingViewActive = !isCoRotatingViewActive;
                    } else {
                        selectedParticleId = clickedId;
                        isCameraLocked = true;
                        isCoRotatingViewActive = true;
                    }
                } else if (isCtrlPressed) {
                    isCoRotatingViewActive = false;
                    if (selectedParticleId == clickedId) {
                        isCameraLocked = !isCameraLocked;
                    } else {
                        selectedParticleId = clickedId;
                        isCameraLocked = true;
                    }
                } else {
                    isCoRotatingViewActive = false;
                    selectedParticleId = clickedId;
                    isCameraLocked = false;
                }
            }
        }
        repaint();
    }

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
                isCoRotatingViewActive = false;
                selectedParticleId = -1;
                activeGapParticleId = -1;
            }
        }

        double maxExpectedRadius = SimulationConfig.DISK_OUTER_RADIUS;
        double maxWindowRadius = Math.min(getWidth() / 2.0, getHeight() / 2.0) * 0.85;
        double baseScale = maxWindowRadius / maxExpectedRadius;
        double scale = baseScale * zoomFactor;

        AffineTransform worldTransform = new AffineTransform();
        double panelCenterX = getWidth() / 2.0;
        double panelCenterY = getHeight() / 2.0;

        if (selectedIdx != -1) {
            if (isCoRotatingViewActive) {
                double px = x[selectedIdx];
                double py = y[selectedIdx];
                double pvx = vx[selectedIdx];
                double pvy = vy[selectedIdx];
                
                double velAngle = Math.atan2(pvy, pvx);

                worldTransform.translate(panelCenterX, panelCenterY);
                worldTransform.scale(scale, scale);
                worldTransform.rotate(-velAngle);
                worldTransform.translate(-px, -py);

                panX = -px * scale;
                panY = -py * scale;
            } else if (isCameraLocked) {
                panX = -x[selectedIdx] * scale;
                panY = -y[selectedIdx] * scale;
                worldTransform.translate(panelCenterX + panX, panelCenterY + panY);
                worldTransform.scale(scale, scale);
            } else {
                worldTransform.translate(panelCenterX + panX, panelCenterY + panY);
                worldTransform.scale(scale, scale);
            }
        } else {
            isCoRotatingViewActive = false;
            worldTransform.translate(panelCenterX + panX, panelCenterY + panY);
            worldTransform.scale(scale, scale);
        }

        Graphics2D g2World = (Graphics2D) g2.create();
        g2World.setTransform(worldTransform);

        // 1. Stella Centrale (disegnata nel mondo)
        g2World.setColor(new Color(255, 180, 50));
        double starRadiusWorld = Math.max(4.0 / scale, 6.0 * Math.sqrt(zoomFactor) / scale);
        g2World.fill(new Ellipse2D.Double(-starRadiusWorld, -starRadiusWorld, starRadiusWorld * 2, starRadiusWorld * 2));

        // 1b. Gap radiali agnostici e mirati
        drawLowDensityRadialGaps(g2World, x, y, count, scale);

        if (activeGapParticleId != -1) {
            int gapIdx = -1;
            for (int i = 0; i < count; i++) {
                if (ids[i] == activeGapParticleId) { gapIdx = i; break; }
            }
            if (gapIdx != -1 && mass[gapIdx] > 0) {
                drawSinglePlanetaryClearingZone(g2World, x, y, count, x[gapIdx], y[gapIdx], vx[gapIdx], vy[gapIdx], mass[gapIdx]);
            } else {
                activeGapParticleId = -1;
            }
        }

        // 2. Top N corpi e orbite
        int[] topIndices = findTopMassiveIndices(mass, count, SimulationConfig.TOP_ORBITS_COUNT);
        double mu = SimulationConfig.G * SimulationConfig.STAR_MASS;
        Color defaultOrbitColor = new Color(255, 255, 255, 70);
        BasicStroke defaultStroke = new BasicStroke((float)(1.0 / scale), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                                10.0f, new float[]{5.0f / (float)scale, 5.0f / (float)scale}, 0.0f);

        for (int idx : topIndices) {
            if (idx != -1 && mass[idx] > 0) {
                drawKeplerianOrbit(g2World, x[idx], y[idx], vx[idx], vy[idx], mu, defaultOrbitColor, defaultStroke);

                double crosshairSize = Math.max(12.0 / scale, (12 * Math.sqrt(zoomFactor)) / scale);
                g2World.setColor(new Color(255, 255, 255, 200));
                g2World.setStroke(new BasicStroke((float)(1.0 / scale)));
                g2World.draw(new Ellipse2D.Double(x[idx] - crosshairSize / 2, y[idx] - crosshairSize / 2, crosshairSize, crosshairSize));
            }
        }

        if (selectedIdx != -1) {
            Color selectedOrbitColor = new Color(255, 215, 0, 220);
            BasicStroke selectedStroke = new BasicStroke((float)(1.5 / scale));
            drawKeplerianOrbit(g2World, x[selectedIdx], y[selectedIdx], vx[selectedIdx], vy[selectedIdx], mu, selectedOrbitColor, selectedStroke);
        }

        // 4. Disegno particelle nel mondo
        for (int i = 0; i < count; i++) {
            double rx = x[i];
            double ry = y[i];
            double r = radius[i];

            if (merged[i]) {
                double radiusRatio = Math.max(1.0, r / MIN_PARTICLE_RADIUS);
                double sizePx = Math.min(24, (3 + Math.log(radiusRatio) * 2.0) * Math.sqrt(zoomFactor));
                sizePx = Math.max(3, sizePx);
                double sizeWorld = sizePx / scale;

                double logRatio = 3.0 * Math.log10(radiusRatio);
                double maxLog = 3.5;
                float factor = (float) Math.min(1.0, Math.max(0.0, logRatio / maxLog));

                float red   = 0.65f + (0.35f * factor);
                float green = 0.30f + (0.60f * factor);
                float blue  = 0.15f + (0.35f * factor);
                float alpha = 0.50f + (0.45f * factor);

                g2World.setColor(new Color(red, green, blue, alpha));
                g2World.fill(new Ellipse2D.Double(rx - sizeWorld / 2, ry - sizeWorld / 2, sizeWorld, sizeWorld));

            } else {
                double radiusRatio = Math.max(1.0, r / MIN_PARTICLE_RADIUS);
                double sizePx = Math.min(16, (1.5 + Math.log(radiusRatio) * 1.5) * Math.sqrt(zoomFactor));
                sizePx = Math.max(1, sizePx);
                double sizeWorld = sizePx / scale;

                double logRatio = 2.5 * Math.log10(radiusRatio);
                double maxLog = 3.5;
                float factor = (float) Math.min(1.0, Math.max(0.0, logRatio / maxLog));

                float red   = 0.15f + (0.25f * factor);
                float green = 0.50f + (0.45f * factor);
                float blue  = 0.85f + (0.15f * factor);
                float alpha = 0.30f + (0.50f * factor);

                g2World.setColor(new Color(red, green, blue, alpha));

                if (sizePx <= 1) {
                    g2World.fill(new Ellipse2D.Double(rx, ry, 1.0 / scale, 1.0 / scale));
                } else {
                    g2World.fill(new Ellipse2D.Double(rx - sizeWorld / 2, ry - sizeWorld / 2, sizeWorld, sizeWorld));
                }
            }

            if (i == selectedIdx) {
                g2World.setColor(Color.YELLOW);
                g2World.setStroke(new BasicStroke((float)(1.2 / scale)));
                double rPx = Math.max(8, 10 * Math.sqrt(zoomFactor));
                double rWorld = rPx / scale;

                g2World.draw(new Ellipse2D.Double(rx - rWorld, ry - rWorld, rWorld * 2, rWorld * 2));

                double lenWorld = 4.0 / scale;
                g2World.draw(new java.awt.geom.Line2D.Double(rx - rWorld - lenWorld, ry, rx - rWorld + (2.0 / scale), ry));
                g2World.draw(new java.awt.geom.Line2D.Double(rx + rWorld - (2.0 / scale), ry, rx + rWorld + lenWorld, ry));
                g2World.draw(new java.awt.geom.Line2D.Double(rx, ry - rWorld - lenWorld, rx, ry - rWorld + (2.0 / scale)));
                g2World.draw(new java.awt.geom.Line2D.Double(rx, ry + rWorld - (2.0 / scale), rx, ry + rWorld + lenWorld));
            }
        }

        g2World.dispose();

        // 5. HUD Selezione e Tempo (su schermo, invariati)
        if (selectedIdx != -1) {
            boolean hasGapActive = (activeGapParticleId == ids[selectedIdx]);
            drawSelectionHUD(g2, ids[selectedIdx], x[selectedIdx], y[selectedIdx],
                             vx[selectedIdx], vy[selectedIdx],
                             mass[selectedIdx], radius[selectedIdx], merged[selectedIdx], hasGapActive, isCoRotatingViewActive);
        }

        drawTimeHUD(g2, simulatedTimeSeconds, currentDtSeconds);

        // 6. Overlay di Pausa
        if (isPaused) {
            drawPauseOverlay(g2);
        }
    }

    private void drawPauseOverlay(Graphics2D g2) {
        String msg = ">>> SIMULAZIONE IN PAUSA [PREMI 'P' PER RIPRENDERE] <<<";
        g2.setFont(g2.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        FontMetrics fm = g2.getFontMetrics();
        int strWidth = fm.stringWidth(msg);
        
        int boxWidth = strWidth + 30;
        int boxHeight = 30;
        int boxX = (getWidth() - boxWidth) / 2;
        int boxY = 15;

        g2.setColor(new Color(120, 20, 20, 220));
        g2.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);

        g2.setColor(new Color(255, 100, 100, 240));
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);

        g2.setColor(Color.WHITE);
        g2.drawString(msg, boxX + 15, boxY + 20);
    }

    private void drawSelectionHUD(Graphics2D g2, int id, float rx, float ry,
                                  float vx, float vy, double m, float r, boolean isMerged, boolean hasGapActive, boolean isCoRotating) {
        int hudX = 15;
        int hudY = 15;
        int hudWidth = 220;
        int hudHeight = 145;

        g2.setColor(new Color(10, 15, 30, 200));
        g2.fillRoundRect(hudX, hudY, hudWidth, hudHeight, 10, 10);

        g2.setColor(new Color(255, 215, 0, 180));
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRoundRect(hudX, hudY, hudWidth, hudHeight, 10, 10);

        int textX = hudX + 12;
        int textY = hudY + 20;
        int lineHeight = 17;

        double rAU = Math.hypot(rx, ry) / SimulationConfig.AU;
        double vKmS = Math.hypot(vx, vy) / 1000.0;
        double radiusKm = r / 1000.0;

        g2.setColor(Color.YELLOW);
        String lockStatus = isCoRotating ? " [CO-ROT]" : (isCameraLocked ? " [LOCKED]" : "");
        g2.drawString(String.format("CORPO ID #%d (%s)%s", id, isMerged ? "Accresciuto" : "Base", lockStatus), textX, textY);

        g2.setColor(Color.WHITE);
        g2.drawString(String.format("Massa: %.3e kg", m), textX, textY + lineHeight);
        g2.drawString(String.format("Raggio: %.1f km", radiusKm), textX, textY + lineHeight * 2);
        g2.drawString(String.format("Distanza R: %.4f AU", rAU), textX, textY + lineHeight * 3);
        g2.drawString(String.format("Velocità V: %.2f km/s", vKmS), textX, textY + lineHeight * 4);
        
        g2.setColor(new Color(0, 255, 180));
        g2.drawString(String.format("Solco [G]: %s", hasGapActive ? "ATTIVO" : "DISATTIVO"), textX, textY + lineHeight * 5);
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

    private void drawKeplerianOrbit(Graphics2D g2World, double rx, double ry, double vx, double vy,
                                    double mu, Color orbitColor, BasicStroke stroke) {
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

        AffineTransform oldTransform = g2World.getTransform();

        g2World.translate(cxWorld, cyWorld);
        g2World.rotate(omega);

        g2World.setColor(orbitColor);
        g2World.setStroke(stroke);

        g2World.draw(new Ellipse2D.Double(-a, -b, 2.0 * a, 2.0 * b));

        g2World.setTransform(oldTransform);
    }

    private void drawLowDensityRadialGaps(Graphics2D g2World, float[] x, float[] y, int count, double scale) {
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
                double avgRadiusWorld = (rInnerWorld + rOuterWorld) / 2.0;
                double thicknessWorld = Math.max(2.0 / scale, (rOuterWorld - rInnerWorld));

                float depthFactor = (float) (1.0 - (binCounts[b] / lowDensityThreshold));
                int alpha = (int) (50 + depthFactor * 110);

                g2World.setColor(new Color(255, 40, 80, alpha));
                g2World.setStroke(new BasicStroke((float) thicknessWorld));

                g2World.draw(new Ellipse2D.Double(-avgRadiusWorld, -avgRadiusWorld, avgRadiusWorld * 2.0, avgRadiusWorld * 2.0));
            }
        }
    }

    private void drawSinglePlanetaryClearingZone(Graphics2D g2World, float[] x, float[] y, int count, 
                                                 double rx, double ry, double vx, double vy, double mass) {
        double mu = SimulationConfig.G * SimulationConfig.STAR_MASS;
        double rMag = Math.hypot(rx, ry);
        if (rMag == 0) return;

        double vMag = Math.hypot(vx, vy);
        double energy = (vMag * vMag / 2.0) - (mu / rMag);
        if (energy >= 0) return; 

        double a = -mu / (2.0 * energy);
        double starMass = SimulationConfig.STAR_MASS;
        double hillRadius = a * Math.cbrt((mass / starMass) / 3.0);
        double clearingHalfWidth = Math.max(hillRadius * 2.5, a * 0.02);

        double rInnerWorld = Math.max(0, a - clearingHalfWidth);
        double rOuterWorld = a + clearingHalfWidth;

        int particlesInAnnulus = 0;
        int particlesInControlZones = 0;
        double controlWidth = clearingHalfWidth;

        for (int i = 0; i < count; i++) {
            double dist = Math.hypot(x[i], y[i]);
            if (dist >= rInnerWorld && dist <= rOuterWorld) {
                particlesInAnnulus++;
            } else if ((dist >= rInnerWorld - controlWidth && dist < rInnerWorld) || 
                   (dist > rOuterWorld && dist <= rOuterWorld + controlWidth)) {
                particlesInControlZones++;
            }
        }

        double clearingDepth = 0.0;
        if (particlesInControlZones > 0) {
            double expectedDensity = particlesInControlZones / 2.0;
            clearingDepth = Math.max(0.0, 1.0 - (particlesInAnnulus / expectedDensity));
        }

        double avgRadiusWorld = (rInnerWorld + rOuterWorld) / 2.0;
        double thicknessWorld = Math.max(2.0, rOuterWorld - rInnerWorld);

        int alpha = (int) (30 + clearingDepth * 150);
        g2World.setColor(new Color(0, 255, 180, Math.min(255, Math.max(20, alpha))));
        g2World.setStroke(new BasicStroke((float) thicknessWorld));
        g2World.draw(new Ellipse2D.Double(-avgRadiusWorld, -avgRadiusWorld, avgRadiusWorld * 2.0, avgRadiusWorld * 2.0));
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