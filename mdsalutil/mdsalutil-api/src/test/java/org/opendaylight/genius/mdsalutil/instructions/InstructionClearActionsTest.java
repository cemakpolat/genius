/*
 * Copyright © 2017 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.genius.mdsalutil.instructions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.opendaylight.genius.mdsalutil.InstructionInfo;
import org.opendaylight.genius.mdsalutil.InstructionType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.instruction.ClearActionsCase;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.list.Instruction;

/**
 * Test for {@link InstructionClearActions}.
 */
public class InstructionClearActionsTest {
    @Test
    public void backwardsCompatibleInstruction() {
        verifyInstructionInfo(new InstructionInfo(InstructionType.clear_actions));
    }

    @Test
    public void newInstruction() {
        verifyInstructionInfo(new InstructionClearActions());
    }

    private void verifyInstructionInfo(InstructionInfo instructionInfo) {
        Instruction instruction = instructionInfo.buildInstruction(2);
        assertEquals(2, instruction.getKey().getOrder().intValue());
        assertTrue(instruction.getInstruction() instanceof ClearActionsCase);
    }
}