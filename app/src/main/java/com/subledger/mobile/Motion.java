package com.subledger.mobile;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Motion shared by the screens: damped springs that keep the finger's velocity, the press-to-shrink
 * feedback on tappable surfaces, and light haptics. Everything runs on {@link ValueAnimator}, so the
 * system "remove animations" setting turns it off.
 */
final class Motion {
    /** Material 3 "emphasized decelerate". */
    static final TimeInterpolator EMPHASIZED = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final TimeInterpolator PRESS = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private Motion() {
    }

    /**
     * Shrinks the view slightly while it is pressed and springs it back on release, like iOS
     * buttons. Works alongside the ripple.
     */
    static void pressable(View view, float scale) {
        StateListAnimator states = new StateListAnimator();
        states.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled},
                scaleTo(scale, 110, PRESS));
        states.addState(new int[0], scaleTo(1f, 420, new Spring.Interpolator(0.62f, 0.42f)));
        view.setStateListAnimator(states);
    }

    private static Animator scaleTo(float scale, long millis, TimeInterpolator interpolator) {
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, scale));
        animator.setDuration(millis);
        animator.setInterpolator(interpolator);
        return animator;
    }

    /** A light tick, e.g. when a sheet snaps to another height or a segment is chosen. */
    static void tick(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** Damped harmonic spring for one value. */
    static final class Spring {
        interface Target {
            void set(float value);
        }

        private final double damping;
        private final double omega;
        private ValueAnimator animator;
        private float target;

        /**
         * @param damping  1 settles without overshoot; lower values bounce
         * @param response seconds for one undamped oscillation; smaller is snappier
         */
        Spring(float damping, float response) {
            this.damping = Math.min(0.999, Math.max(0.05, damping));
            this.omega = 2 * Math.PI / response;
        }

        boolean running() {
            return animator != null;
        }

        float target() {
            return target;
        }

        void cancel() {
            ValueAnimator running = animator;
            animator = null;
            if (running != null) {
                running.cancel();
            }
        }

        /** Animates from {@code from} to {@code to}, starting at {@code velocity} units per second. */
        void start(float from, float to, float velocity, Target update, Runnable end) {
            cancel();
            target = to;
            double decay = damping * omega;
            double damped = omega * Math.sqrt(1 - damping * damping);
            double x0 = from - to;
            double b = (velocity + decay * x0) / damped;
            // Run until the envelope is below half a pixel.
            double amplitude = Math.abs(x0) + Math.abs(b);
            double seconds = amplitude <= 0.5 ? 0 : Math.min(1.4, Math.log(amplitude / 0.5) / decay);
            ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
            next.setInterpolator(new LinearInterpolator());
            next.setDuration(Math.max(1, Math.round(seconds * 1000)));
            next.addUpdateListener(animation -> {
                if (animator != animation) {
                    return;
                }
                double t = animation.getAnimatedFraction() * seconds;
                double x = Math.exp(-decay * t) * (x0 * Math.cos(damped * t) + b * Math.sin(damped * t));
                update.set((float) (to + x));
            });
            next.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animator != animation) {
                        return;
                    }
                    animator = null;
                    update.set(to);
                    if (end != null) {
                        end.run();
                    }
                }
            });
            animator = next;
            next.start();
        }

        /** The same spring as a 0→1 interpolator, for animators that need one. */
        static final class Interpolator implements TimeInterpolator {
            private final double decay, damped;
            private final double seconds;

            Interpolator(float damping, float response) {
                double zeta = Math.min(0.999, Math.max(0.05, damping));
                double omega = 2 * Math.PI / response;
                decay = zeta * omega;
                damped = omega * Math.sqrt(1 - zeta * zeta);
                seconds = response;
            }

            @Override
            public float getInterpolation(float input) {
                double t = input * seconds * 1.6;
                double x = Math.exp(-decay * t) * (Math.cos(damped * t) + decay / damped * Math.sin(damped * t));
                return input >= 1f ? 1f : (float) (1 - x);
            }
        }
    }
}
