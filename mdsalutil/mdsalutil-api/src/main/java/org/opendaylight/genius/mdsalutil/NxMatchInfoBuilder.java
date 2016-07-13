/*
 * Copyright (c) 2016 Red Hat and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.genius.mdsalutil;

/**
 * Builder for NxMatchInfo.
 * This class, even if not directly called from anywhere statically, is needed
 * by the XtendBeanGenerator in order to be able to generate code which creates
 * MatchInfo instances.
 */
public class NxMatchInfoBuilder extends AbstractMatchInfoBaseBuilder<NxMatchInfo> {

    private NxMatchFieldType matchField;

    @Override
    public NxMatchInfo build() {
        if (matchField == null) {
            throw new IllegalStateException("matchField must be set");
        } else if (matchValues != null && bigMatchValues == null && stringMatchValues == null) {
            return new NxMatchInfo(matchField, matchValues);
        } else if (matchValues == null && bigMatchValues != null && stringMatchValues == null) {
            return new NxMatchInfo(matchField, bigMatchValues);
        } else if (matchValues == null && bigMatchValues == null && stringMatchValues != null) {
            return new NxMatchInfo(matchField, stringMatchValues);
        } else {
            throw new IllegalStateException("Can only use either matchValues or bigMatchValues or stringMatchValues");
        }
    }

    public NxMatchFieldType getMatchField() {
        return matchField;
    }

    public void setMatchField(NxMatchFieldType matchField) {
        this.matchField = matchField;
    }

}
