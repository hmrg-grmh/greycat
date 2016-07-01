package org.mwg.core.task;

import org.junit.Assert;
import org.junit.Test;
import org.mwg.task.Action;
import org.mwg.task.Task;
import org.mwg.task.TaskContext;
import org.mwg.task.TaskFunctionConditional;

import static org.mwg.task.Actions.*;

public class ActionIfThenTest extends AbstractActionTest {

    @Test
    public void test() {
        initGraph();
        final boolean[] result = {false, false};

        Task modifyResult0 = then(new Action() {
            @Override
            public void eval(TaskContext context) {
                result[0] = true;
            }
        });

        Task modifyResult1 = then(new Action() {
            @Override
            public void eval(TaskContext context) {
                result[0] = true;
            }
        });


        new CoreTask().ifThen(new TaskFunctionConditional() {
            @Override
            public boolean eval(TaskContext context) {
                return true;
            }
        }, modifyResult0).execute(graph,null);

        new CoreTask().ifThen(new TaskFunctionConditional() {
            @Override
            public boolean eval(TaskContext context) {
                return false;
            }
        }, modifyResult0).execute(graph,null);

        Assert.assertEquals(true, result[0]);
        Assert.assertEquals(false, result[1]);
        removeGraph();
    }

    @Test
    public void testChainAfterIfThen() {
        initGraph();
        Task addVarInContext = inject(5).asVar("variable").then(new Action() {
            @Override
            public void eval(TaskContext context) {
                //empty action
            }
        });

        new CoreTask().ifThen(new TaskFunctionConditional() {
            @Override
            public boolean eval(TaskContext context) {
                return true;
            }
        }, addVarInContext).fromVar("variable").then(new Action() {
            @Override
            public void eval(TaskContext context) {
                Integer val = (Integer) context.result();
                Assert.assertEquals(5, (int) val);
            }
        }).execute(graph,null);
        removeGraph();
    }

    @Test
    public void accessContextVariableInThenTask() {
        initGraph();
        Task accessVar = then(new Action() {
            @Override
            public void eval(TaskContext context) {
                Integer variable = (Integer) context.variable("variable");
                Assert.assertEquals(5, (int) variable);
            }
        });

        inject(5).asVar("variable").ifThen(new TaskFunctionConditional() {
            @Override
            public boolean eval(TaskContext context) {
                return true;
            }
        }, accessVar).execute(graph,null);
        removeGraph();
    }
}
