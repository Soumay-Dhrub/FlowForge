/**
 * One {@link com.flowforge.engine.NodeExecutor} per node type (Requirement 9.2).
 *
 * <p>Each executor is a Spring bean that {@link com.flowforge.engine.NodeExecutorFactory} indexes at
 * startup, so adding a node type means adding a class here and nothing else. They share three helpers:
 * {@link com.flowforge.engine.executors.NodeConfig} for typed reads of a node's {@code config_json},
 * {@link com.flowforge.engine.executors.AssigneeResolver} for turning a configured user or role
 * into actual people, and {@link com.flowforge.engine.executors.ConditionEvaluator} for the sandboxed
 * evaluation of an edge's condition expression.
 *
 * <p>No executor depends on {@link com.flowforge.engine.WorkflowEngineService}: the engine depends on
 * the factory and the factory on every executor, so such a dependency would close a startup cycle.
 * Executors that need an engine-level transition take the collaborator that owns it — the Condition
 * node takes {@link com.flowforge.engine.InstanceErrorRecorder} for the ERROR transition of
 * Requirement 9.5.
 *
 * <p>Task 17 delivers Start, End, Task and Notification; task 18 Condition and Approval. AND-Join
 * follows in task 19.
 */
package com.flowforge.engine.executors;
