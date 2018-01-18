/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.nodes;

import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InliningLog {
    public static final class BytecodePositionWithId extends BytecodePosition implements Comparable<BytecodePositionWithId> {
        private final int id;

        public BytecodePositionWithId(BytecodePositionWithId caller, ResolvedJavaMethod method, int bci, int id) {
            super(caller, method, bci);
            this.id = id;
        }

        public BytecodePositionWithId(BytecodePosition position, int id) {
            super(toPositionWithId(position.getCaller()), position.getMethod(), position.getBCI());
            this.id = id;
        }

        public BytecodePositionWithId addCallerWithId(BytecodePositionWithId caller) {
            if (getCaller() == null) {
                return new BytecodePositionWithId(caller, getMethod(), getBCI(), id);
            } else {
                return new BytecodePositionWithId(getCaller().addCallerWithId(caller), getMethod(), getBCI(), id);
            }
        }

        private static BytecodePositionWithId toPositionWithId(BytecodePosition position) {
            if (position == null) {
                return null;
            }
            return new BytecodePositionWithId(toPositionWithId(position.getCaller()), position.getMethod(), position.getBCI(), 0);
        }

        @Override
        public BytecodePositionWithId getCaller() {
            return (BytecodePositionWithId) super.getCaller();
        }

        public BytecodePositionWithId withoutCaller() {
            return new BytecodePositionWithId(null, getMethod(), getBCI(), id);
        }

        public long getId() {
            return id;
        }

        @Override
        public boolean equals(Object that) {
            if (!(that instanceof BytecodePositionWithId)) {
                return false;
            }
            return super.equals(that) && this.id == ((BytecodePositionWithId) that).id;
        }

        @Override
        public int hashCode() {
            return super.hashCode() ^ (id << 16);
        }

        public int compareTo(BytecodePositionWithId that) {
            int diff = this.getBCI() - that.getBCI();
            if (diff != 0) {
                return diff;
            }
            diff = (int) (this.getId() - that.getId());
            return diff;
        }
    }

    public static final class Decision {
        private final boolean positive;
        private final String reason;
        private final String phase;
        private final BytecodePositionWithId position;
        private final InliningLog childLog;

        private Decision(boolean positive, String reason, String phase, BytecodePositionWithId position, InliningLog childLog) {
            assert position != null;
            this.positive = positive;
            this.reason = reason;
            this.phase = phase;
            this.position = position;
            this.childLog = childLog;
        }

        public boolean isPositive() {
            return positive;
        }

        public String getReason() {
            return reason;
        }

        public String getPhase() {
            return phase;
        }

        public BytecodePositionWithId getPosition() {
            return position;
        }

        public InliningLog getChildLog() {
            return childLog;
        }
    }

    private static class Callsite {
        public String decision;
        public final Map<BytecodePositionWithId, Callsite> children;
        public final BytecodePositionWithId position;

        Callsite(BytecodePositionWithId position) {
            this.children = new HashMap<>();
            this.position = position;
        }

        public Callsite getOrCreateChild(BytecodePositionWithId position) {
            Callsite child = children.get(position.withoutCaller());
            if (child == null) {
                child = new Callsite(position);
                children.put(position.withoutCaller(), child);
            }
            return child;
        }

        public Callsite createCallsite(BytecodePositionWithId position, String decision) {
            Callsite parent = getOrCreateCallsite(position.getCaller());
            Callsite callsite = parent.getOrCreateChild(position);
            callsite.decision = decision;
            return null;
        }

        private Callsite getOrCreateCallsite(BytecodePositionWithId position) {
            if (position == null) {
                return this;
            } else {
                Callsite parent = getOrCreateCallsite(position.getCaller());
                Callsite callsite = parent.getOrCreateChild(position);
                return callsite;
            }
        }
    }

    private final List<Decision> decisions;

    public InliningLog() {
        this.decisions = new ArrayList<>();
    }

    public List<Decision> getDecisions() {
        return decisions;
    }

    public void addDecision(boolean positive, String reason, String phase, BytecodePositionWithId position, InliningLog calleeLog) {
        Decision decision = new Decision(positive, reason, phase, position, calleeLog);
        decisions.add(decision);
    }

    public String formatAsList() {
        StringBuilder builder = new StringBuilder();
        formatAsList("", null, decisions, builder);
        return builder.toString();
    }

    private void formatAsList(String phasePrefix, BytecodePositionWithId caller, List<Decision> decisions, StringBuilder builder) {
        for (Decision decision : decisions) {
            String phaseStack = phasePrefix.equals("") ? decision.getPhase() : phasePrefix + "-" + decision.getPhase();
            String positive = decision.isPositive() ? "inline" : "do not inline";
            BytecodePositionWithId absolutePosition = decision.getPosition().addCallerWithId(caller);
            String position = "  " + decision.getPosition().toString().replaceAll("\n", "\n  ");
            String line = String.format("<%s> %s: %s\n%s", phaseStack, positive, decision.getReason(), position);
            builder.append(line).append(System.lineSeparator());
            if (decision.getChildLog() != null) {
                formatAsList(phaseStack, absolutePosition, decision.getChildLog().getDecisions(), builder);
            }
        }
    }

    public String formatAsTree() {
        Callsite root = new Callsite(null);
        createTree("", null, root, decisions);
        StringBuilder builder = new StringBuilder();
        formatAsTree(root, "", builder);
        return builder.toString();
    }

    private void createTree(String phasePrefix, BytecodePositionWithId caller, Callsite root, List<Decision> decisions) {
        for (Decision decision : decisions) {
            String phaseStack = phasePrefix.equals("") ? decision.getPhase() : phasePrefix + "-" + decision.getPhase();
            String positive = decision.isPositive() ? "inline" : "do not inline";
            BytecodePositionWithId absolutePosition = decision.getPosition().addCallerWithId(caller);
            String line = String.format("<%s> %s: %s", phaseStack, positive, decision.getReason());
            root.createCallsite(absolutePosition, line);
            if (decision.getChildLog() != null) {
                createTree(phaseStack, absolutePosition, root, decision.getChildLog().getDecisions());
            }
        }
    }

    private void formatAsTree(Callsite site, String indent, StringBuilder builder) {
        String position = site.position != null ? site.position.withoutCaller().toString() : "<root>";
        String decision = site.decision != null ? site.decision : "";
        String line = String.format("%s%s; %s", indent, position, decision);
        builder.append(line).append(System.lineSeparator());
        String childIndent = indent + "  ";
        site.children.entrySet().stream().sorted((x, y) -> x.getKey().compareTo(y.getKey()))
                        .forEach(e -> formatAsTree(e.getValue(), childIndent, builder));
    }
}
