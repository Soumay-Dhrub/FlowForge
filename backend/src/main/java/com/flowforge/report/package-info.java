/**
 * Reporting: the personal dashboard and aggregate workflow metrics
 * (Requirements 20.1–20.3, 21.1–21.5).
 *
 * <p>Everything here reads. No class in this package writes to a workflow, a task or an approval, and
 * every service method is {@code @Transactional(readOnly = true)} — a report that could change the
 * thing it measures would make the numbers unreproducible.
 *
 * <p>Where a view already exists elsewhere, the dashboard composes it rather than re-querying:
 * {@code TaskService} owns what a task looks like to a reviewer, and {@code WorkflowInstanceService}
 * owns what a request looks like to its initiator. Duplicating those mappings here would let the
 * dashboard and the task list disagree about the same row.
 *
 * <p>The metrics side queries directly through its own read-only repository, because no existing finder
 * loads a workflow's whole instance population with the associations an aggregate needs.
 */
package com.flowforge.report;
