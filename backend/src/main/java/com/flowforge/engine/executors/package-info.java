/**
 * One {@link com.flowforge.engine.NodeExecutor} per node type (Requirement 9.2).
 *
 * <p>Each executor is a Spring bean that {@link com.flowforge.engine.NodeExecutorFactory} indexes at
 * startup, so adding a node type means adding a class here and nothing else. They share two helpers:
 * {@link com.flowforge.engine.executors.NodeConfig} for typed reads of a node's {@code config_json},
 * and {@link com.flowforge.engine.executors.AssigneeResolver} for turning a configured user or role
 * into actual people.
 *
 * <p>Task 17 delivers Start, End, Task and Notification. Condition and Approval follow in task 18, and
 * AND-Join in task 19.
 */
package com.flowforge.engine.executors;
