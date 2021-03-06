package azkaban.flow;

import org.easymock.Capture;
import org.easymock.IAnswer;
import org.easymock.classextension.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import azkaban.common.utils.Props;

/**
 *
 */
public class MultipleDependencyExecutableFlowTest
{
    private volatile ExecutableFlow dependerFlow;
    private volatile ExecutableFlow dependeeFlow;
    private MultipleDependencyExecutableFlow flow;

    @Before
    public void setUp() throws Exception
    {
        dependerFlow = EasyMock.createMock(ExecutableFlow.class);
        dependeeFlow = EasyMock.createMock(ExecutableFlow.class);

        EasyMock.expect(dependerFlow.getStatus()).andReturn(Status.READY).once();
        EasyMock.expect(dependeeFlow.getStatus()).andReturn(Status.READY).times(2);

        EasyMock.expect(dependeeFlow.getStartTime()).andReturn(null).once();

        EasyMock.replay(dependerFlow, dependeeFlow);

        flow = new MultipleDependencyExecutableFlow("blah", dependerFlow, dependeeFlow);

        EasyMock.verify(dependerFlow, dependeeFlow);
        EasyMock.reset(dependerFlow, dependeeFlow);
    }

    @After
    public void tearDown() throws Exception
    {
        EasyMock.verify(dependerFlow);
        EasyMock.verify(dependeeFlow);
    }

    @Test
    public void testSanity() throws Exception
    {
        final AtomicBoolean dependeeRan = new AtomicBoolean(false);

        final Capture<FlowCallback> dependeeCallback = new Capture<FlowCallback>();
        dependeeFlow.execute(EasyMock.capture(dependeeCallback), EasyMock.isA(Props.class));
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                Assert.assertTrue("Dependee already ran!?", dependeeRan.compareAndSet(false, true));
                Assert.assertEquals(Status.RUNNING, flow.getStatus());

                dependeeCallback.getValue().completed(Status.SUCCEEDED);

                return null;
            }
        }).once();

        EasyMock.expect(dependeeFlow.getStatus()).andReturn(Status.SUCCEEDED).once();
        
        // These are called by mockFlow1/2.getValue().completed(Status.SUCCEEDED);
        EasyMock.expect(dependeeFlow.getFlowGeneratedProperties()).andReturn(new Props()).once();
        EasyMock.expect(dependeeFlow.getName()).andReturn("dependee").once();

        final Capture<FlowCallback> dependerCallback = new Capture<FlowCallback>();
        dependerFlow.execute(EasyMock.capture(dependerCallback), EasyMock.isA(Props.class));
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                Assert.assertTrue("Dependee must run before depender", dependeeRan.get());
                Assert.assertEquals(Status.RUNNING, flow.getStatus());

                dependerCallback.getValue().completed(Status.SUCCEEDED);

                return null;
            }
        }).once();
        
        // These are called by mockFlow1/2.getValue().completed(Status.SUCCEEDED);
        EasyMock.expect(dependerFlow.getFlowGeneratedProperties()).andReturn(new Props()).once();
        EasyMock.expect(dependerFlow.getName()).andReturn("depender").times(2);

        EasyMock.replay(dependerFlow, dependeeFlow);

        Assert.assertEquals(Status.READY, flow.getStatus());

        AtomicBoolean callbackRan = new AtomicBoolean(false);
        flow.execute(new OneCallFlowCallback(callbackRan) {
            @Override
            protected void theCallback(Status status)
            {
                Assert.assertEquals(Status.SUCCEEDED, status);
            }
        }, new Props());

        Assert.assertTrue("Internal flow executes never ran.", dependeeRan.get());
        Assert.assertTrue("Callback didn't run.", callbackRan.get());
        Assert.assertEquals(Status.SUCCEEDED, flow.getStatus());
        Assert.assertEquals(null, flow.getException());

        callbackRan = new AtomicBoolean(false);
        flow.execute(new OneCallFlowCallback(callbackRan) {
            @Override
            protected void theCallback(Status status)
            {
                Assert.assertEquals(Status.SUCCEEDED, status);
            }
        }, new Props());

        Assert.assertTrue("Callback didn't run.", callbackRan.get());
        Assert.assertEquals(Status.SUCCEEDED, flow.getStatus());
        Assert.assertEquals(null, flow.getException());
    }

    @Test
    public void testFailureInDependee() throws Exception
    {
        final RuntimeException theException = new RuntimeException();
        final AtomicBoolean dependeeRan = new AtomicBoolean(false);

        final Capture<FlowCallback> dependeeCallback = new Capture<FlowCallback>();
        dependeeFlow.execute(EasyMock.capture(dependeeCallback), EasyMock.isA(Props.class));
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                Assert.assertTrue("Dependee already ran!?", dependeeRan.compareAndSet(false, true));
                Assert.assertEquals(Status.RUNNING, flow.getStatus());

                dependeeCallback.getValue().completed(Status.FAILED);

                return null;
            }
        }).once();

        EasyMock.expect(dependeeFlow.getStatus()).andReturn(Status.FAILED).once();
        EasyMock.expect(dependeeFlow.getException()).andReturn(theException).once();

        EasyMock.replay(dependerFlow, dependeeFlow);

        Assert.assertEquals(Status.READY, flow.getStatus());

        AtomicBoolean callbackRan = new AtomicBoolean(false);
        flow.execute(new OneCallFlowCallback(callbackRan) {
            @Override
            protected void theCallback(Status status)
            {
                Assert.assertEquals(Status.FAILED, status);
            }
        }, new Props());

        Assert.assertTrue("Internal flow executes never ran.", dependeeRan.get());
        Assert.assertTrue("Callback didn't run.", callbackRan.get());
        Assert.assertEquals(Status.FAILED, flow.getStatus());
        Assert.assertEquals(theException, flow.getException());

        callbackRan = new AtomicBoolean(false);
        flow.execute(new OneCallFlowCallback(callbackRan) {
            @Override
            protected void theCallback(Status status)
            {
                Assert.assertEquals(Status.FAILED, status);
            }
        }, null);

        Assert.assertTrue("Callback didn't run.", callbackRan.get());
        Assert.assertEquals(Status.FAILED, flow.getStatus());
        Assert.assertEquals(theException, flow.getException());

        EasyMock.verify(dependerFlow, dependeeFlow);
        EasyMock.reset(dependerFlow, dependeeFlow);

        EasyMock.expect(dependerFlow.reset()).andReturn(true).once();

        EasyMock.replay(dependerFlow, dependeeFlow);

        Assert.assertTrue("Expected to be able to reset the flow", flow.reset());
        Assert.assertEquals(Status.READY, flow.getStatus());
        Assert.assertEquals(null, flow.getException());
    }

    @Test
    public void testFailureInDepender() throws Exception
    {
        final RuntimeException theException = new RuntimeException();
        final AtomicBoolean dependeeRan = new AtomicBoolean(false);

        final Capture<FlowCallback> dependeeCallback = new Capture<FlowCallback>();
        dependeeFlow.execute(EasyMock.capture(dependeeCallback), EasyMock.isA(Props.class));
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                Assert.assertTrue("Dependee already ran!?", dependeeRan.compareAndSet(false, true));
                Assert.assertEquals(Status.RUNNING, flow.getStatus());

                dependeeCallback.getValue().completed(Status.SUCCEEDED);

                return null;
            }
        }).once();

        EasyMock.expect(dependeeFlow.getStatus()).andReturn(Status.SUCCEEDED).once();
        // These are called by dependee.getValue().completed(Status.SUCCEEDED);
        EasyMock.expect(dependeeFlow.getFlowGeneratedProperties()).andReturn(new Props()).once();
        EasyMock.expect(dependeeFlow.getName()).andReturn("dependee").times(1);
        EasyMock.expect(dependerFlow.getName()).andReturn("depender").times(1);

        final Capture<FlowCallback> dependerCallback = new Capture<FlowCallback>();
        dependerFlow.execute(EasyMock.capture(dependerCallback), EasyMock.isA(Props.class));
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                Assert.assertTrue("Dependee must run before depender", dependeeRan.get());
                Assert.assertEquals(Status.RUNNING, flow.getStatus());

                dependerCallback.getValue().completed(Status.FAILED);

                return null;
            }
        }).once();
        EasyMock.expect(dependerFlow.getException()).andReturn(theException).once();

        EasyMock.replay(dependerFlow, dependeeFlow);

        Assert.assertEquals(Status.READY, flow.getStatus());

        AtomicBoolean callbackRan = new AtomicBoolean(false);
        flow.execute(new OneCallFlowCallback(callbackRan) {
            @Override
            protected void theCallback(Status status)
            {
                Assert.assertEquals(Status.FAILED, status);
            }
        }, new Props());

        Assert.assertTrue("Internal flow executes never ran.", dependeeRan.get());
        Assert.assertTrue("Callback didn't run.", callbackRan.get());
        Assert.assertEquals(Status.FAILED, flow.getStatus());
        Assert.assertEquals(theException, flow.getException());

        callbackRan = new AtomicBoolean(false);
        flow.execute(new OneCallFlowCallback(callbackRan) {
            @Override
            protected void theCallback(Status status)
            {
                Assert.assertEquals(Status.FAILED, status);
            }
        }, null);

        Assert.assertTrue("Callback didn't run.", callbackRan.get());
        Assert.assertEquals(Status.FAILED, flow.getStatus());
        Assert.assertEquals(theException, flow.getException());

        EasyMock.verify(dependerFlow, dependeeFlow);
        EasyMock.reset(dependerFlow, dependeeFlow);

        EasyMock.expect(dependerFlow.reset()).andReturn(true).once();

        EasyMock.replay(dependerFlow, dependeeFlow);

        Assert.assertTrue("Expected to be able to reset the flow", flow.reset());
        Assert.assertEquals(Status.READY, flow.getStatus());
        Assert.assertEquals(null, flow.getException());
    }

    @Test
    public void testAllExecutesHaveTheirCallbackCalled() throws Exception
    {
        final AtomicBoolean dependeeRan = new AtomicBoolean(false);
        final AtomicBoolean executeCallWhileStateWasRunningHadItsCallbackCalled = new AtomicBoolean(false);

        final Capture<FlowCallback> dependeeCallback = new Capture<FlowCallback>();
        dependeeFlow.execute(EasyMock.capture(dependeeCallback), EasyMock.isA(Props.class));
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                Assert.assertTrue("Dependee already ran!?", dependeeRan.compareAndSet(false, true));
                Assert.assertEquals(Status.RUNNING, flow.getStatus());

                flow.execute(new OneCallFlowCallback(executeCallWhileStateWasRunningHadItsCallbackCalled)
                {
                    @Override
                    protected void theCallback(Status status)
                    {
                    }
                }, null);

                dependeeCallback.getValue().completed(Status.SUCCEEDED);

                Assert.assertEquals(Status.SUCCEEDED, flow.getStatus());

                return null;
            }
        }).once();

        EasyMock.expect(dependeeFlow.getStatus()).andReturn(Status.SUCCEEDED).once();
        // These are called by dependee.getValue().completed(Status.SUCCEEDED);
        EasyMock.expect(dependeeFlow.getFlowGeneratedProperties()).andReturn(new Props()).once();
        EasyMock.expect(dependeeFlow.getName()).andReturn("dependee").times(1);
        EasyMock.expect(dependerFlow.getName()).andReturn("depender").times(1);

        final Capture<FlowCallback> dependerCallback = new Capture<FlowCallback>();
        dependerFlow.execute(EasyMock.capture(dependerCallback), EasyMock.isA(Props.class));
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                Assert.assertTrue("Dependee must run before depender", dependeeRan.get());
                Assert.assertEquals(Status.RUNNING, flow.getStatus());

                dependerCallback.getValue().completed(Status.SUCCEEDED);

                Assert.assertEquals(Status.SUCCEEDED, flow.getStatus());

                return null;
            }
        }).once();
        
        // These are called by dependee.getValue().completed(Status.SUCCEEDED);
        EasyMock.expect(dependerFlow.getFlowGeneratedProperties()).andReturn(new Props()).once();
        EasyMock.expect(dependerFlow.getName()).andReturn("depender").times(1);

        EasyMock.replay(dependerFlow, dependeeFlow);

        Assert.assertEquals(Status.READY, flow.getStatus());

        AtomicBoolean callbackRan = new AtomicBoolean(false);
        flow.execute(new OneCallFlowCallback(callbackRan) {
            @Override
            protected void theCallback(Status status)
            {
                Assert.assertEquals(Status.SUCCEEDED, status);
            }
        }, new Props());

        Assert.assertTrue("Internal flow executes never ran.", dependeeRan.get());
        Assert.assertTrue("Callback didn't run.", callbackRan.get());
        Assert.assertEquals(Status.SUCCEEDED, flow.getStatus());
        Assert.assertTrue("dependeeFlow, upon completion, sends another execute() call to the flow.  " +
                          "The callback from that execute call was apparently not called.",
                          executeCallWhileStateWasRunningHadItsCallbackCalled.get());
        Assert.assertEquals(null, flow.getException());

        callbackRan = new AtomicBoolean(false);
        flow.execute(new OneCallFlowCallback(callbackRan) {
            @Override
            protected void theCallback(Status status)
            {
                Assert.assertEquals(Status.SUCCEEDED, status);
            }
        }, null);

        Assert.assertTrue("Callback didn't run.", callbackRan.get());
        Assert.assertEquals(Status.SUCCEEDED, flow.getStatus());
        Assert.assertEquals(null, flow.getException());
    }

    @Test
    public void testChildren() throws Exception
    {
        EasyMock.replay(dependeeFlow, dependerFlow);

        Assert.assertTrue("ComposedExecutableFlow should have children.", flow.hasChildren());
        Assert.assertEquals(1, flow.getChildren().size());
        Assert.assertEquals(dependeeFlow, flow.getChildren().get(0));
    }
}
