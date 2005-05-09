/*******************************************************************************
 * Copyright (c) 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ui.operations;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.operations.IOperationHistory;
import org.eclipse.core.commands.operations.IOperationHistoryListener;
import org.eclipse.core.commands.operations.IUndoContext;
import org.eclipse.core.commands.operations.IUndoableOperation;
import org.eclipse.core.commands.operations.OperationHistoryEvent;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.internal.WorkbenchMessages;
import org.eclipse.ui.internal.WorkbenchPlugin;
import org.eclipse.ui.internal.operations.TimeTriggeredProgressMonitorDialog;

/**
 * <p>
 * OperationHistoryActionHandler implements common behavior for the undo and
 * redo actions. It supports filtering of undo or redo on a particular context.
 * (A null context will cause undo and redo to be disabled.)
 * </p>
 * <p>
 * OperationHistoryActionHandler provides an adapter in the info parameter of
 * the IOperationHistory undo and redo methods that is used to get UI info for
 * prompting the user during operations or operation approval. Adapters are
 * provided for org.eclipse.ui.IWorkbenchWindow, org.eclipse.swt.widgets.Shell,
 * and org.eclipse.ui.IWorkbenchPart.
 * </p>
 * <p>
 * OperationHistoryActionHandler assumes a linear undo/redo model. When the
 * handler is run, the operation history is asked to perform the most recent
 * undo for the handler's context. The handler can be configured (using
 * #setPruneHistory(true) to flush the operation undo or redo history for its
 * context when there is no valid operation on top of the history. This avoids
 * keeping a stale history of invalid operations. By default, pruning does not
 * occur and it is assumed that clients of the particular undo context are
 * pruning the history when necessary.
 * </p>
 * 
 * @since 3.1
 */
public abstract class OperationHistoryActionHandler extends Action implements
		ActionFactory.IWorkbenchAction, IAdaptable {

	private static final int MAX_LABEL_LENGTH = 32;

	private class PartListener implements IPartListener {
		/**
		 * @see IPartListener#partActivated(IWorkbenchPart)
		 */
		public void partActivated(IWorkbenchPart part) {
		}

		/**
		 * @see IPartListener#partBroughtToTop(IWorkbenchPart)
		 */
		public void partBroughtToTop(IWorkbenchPart part) {
		}

		/**
		 * @see IPartListener#partClosed(IWorkbenchPart)
		 */
		public void partClosed(IWorkbenchPart part) {
			if (part.equals(site.getPart())) {
				dispose();
			}
		}

		/**
		 * @see IPartListener#partDeactivated(IWorkbenchPart)
		 */
		public void partDeactivated(IWorkbenchPart part) {
		}

		/**
		 * @see IPartListener#partOpened(IWorkbenchPart)
		 */
		public void partOpened(IWorkbenchPart part) {
		}

	}
	private class HistoryListener implements IOperationHistoryListener {
		public void historyNotification(OperationHistoryEvent event) {
			Display display = getWorkbenchWindow().getWorkbench().getDisplay();
			switch (event.getEventType()) {
			case OperationHistoryEvent.OPERATION_ADDED:
			case OperationHistoryEvent.OPERATION_REMOVED:
			case OperationHistoryEvent.UNDONE:
			case OperationHistoryEvent.REDONE:
				if (display != null && event.getOperation().hasContext(undoContext)) {
					display.asyncExec(new Runnable() {
						public void run() {
							update();
						}
					});
				}
				break;
			case OperationHistoryEvent.OPERATION_NOT_OK:
				if (display != null && event.getOperation().hasContext(undoContext)) {
					display.asyncExec(new Runnable() {
						public void run() {
							if (pruning)
								flush();
							else
								update();
						}
					});
				}
				break;
			case OperationHistoryEvent.OPERATION_CHANGED:
				if (display != null && event.getOperation() == getOperation()) {
					display.asyncExec(new Runnable() {
						public void run() {
							update();
						}
					});
				}
				break;
			}
		}
	}
	private boolean pruning = false;
	private IPartListener partListener = new PartListener();
	private IOperationHistoryListener historyListener = new HistoryListener();
	private TimeTriggeredProgressMonitorDialog progressDialog;
	IUndoContext undoContext = null;
	IWorkbenchPartSite site;

	/**
	 * Construct an operation history action for the specified workbench window
	 * with the specified undo context.
	 * 
	 * @param site -
	 *            the workbench part site for the action.
	 * @param context -
	 *            the undo context to be used
	 */
	OperationHistoryActionHandler(IWorkbenchPartSite site, IUndoContext context) {
		// string will be reset inside action
		super(""); //$NON-NLS-1$
		this.site = site;
		undoContext = context;
		site.getPage().addPartListener(partListener);
		getHistory().addOperationHistoryListener(historyListener);
		// An update must be forced in case the undo limit is 0.
		// see bug #89707
		update();
	}

    /*
     * (non-Javadoc) 
     * @see org.eclipse.ui.actions.ActionFactory.IWorkbenchAction#dispose()
     */
	public void dispose() {
		getHistory().removeOperationHistoryListener(historyListener);
		site.getPage().removePartListener(partListener);
		progressDialog = null;
		// we do not do anything to the history for our context because it may
		// be used elsewhere.
	}

	/*
	 * Flush the history associated with this action.
	 */
	abstract void flush();

	/*
	 * Return the string describing the command.
	 */
	abstract String getCommandString();

	/*
	 * Return the operation history we are using.
	 */
	IOperationHistory getHistory() {
		return getWorkbenchWindow().getWorkbench().getOperationSupport()
				.getOperationHistory();
	}

	/*
	 * Return the current operation.
	 */
	abstract IUndoableOperation getOperation();

    /*
     * (non-Javadoc) 
     * @see org.eclipse.ui.actions.ActionFactory.IWorkbenchAction#run()
     */
	public final void run() {
		Shell parent= getWorkbenchWindow().getShell();
		progressDialog = new TimeTriggeredProgressMonitorDialog(parent, getWorkbenchWindow().getWorkbench().getProgressService().getLongOperationTime());
		IRunnableWithProgress runnable= new IRunnableWithProgress(){
			public void run(IProgressMonitor pm) throws InvocationTargetException {
				try {
					runCommand(pm);
				} catch (ExecutionException e) {
					if (pruning)
						flush();
					throw new InvocationTargetException(e);
				}
			}
		};
		try {
			progressDialog.run(false, true, runnable);
		} catch (InvocationTargetException e) {
			reportException(e);
		} catch (InterruptedException e) {
			// Operation was cancelled and acknowledged by runnable with this exception.
			// Do nothing.
		} catch (OperationCanceledException e) {
			// the operation was cancelled.  Do nothing.
		} finally {
			progressDialog = null;
		}
	}
	
	abstract IStatus runCommand(IProgressMonitor pm) throws ExecutionException;

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.core.runtime.IAdaptable#getAdapter(java.lang.Class)
	 */
	public Object getAdapter(Class adapter) {
		if (adapter.equals(Shell.class)) {
			return getWorkbenchWindow().getShell();
		}
		if (adapter.equals(IWorkbenchWindow.class)) {
			return getWorkbenchWindow();
		}
		if (adapter.equals(IWorkbenchPart.class)) {
			return site.getPart();
		}
		if (adapter.equals(IUndoContext.class)) {
			return undoContext;
		}
		if (adapter.equals(IProgressMonitor.class)) {
			if (progressDialog != null)
				return progressDialog.getProgressMonitor();
		}
		return null;
	}

	/*
	 * Return the workbench window for this action handler
	 */
	private IWorkbenchWindow getWorkbenchWindow() {
		return site.getWorkbenchWindow();
	}

	/**
	 * The undo and redo subclasses should implement this.
	 * 
	 * @return - a boolean indicating enablement state
	 */
	abstract boolean shouldBeEnabled();

	/**
	 * Set the context shown by the handler. Normally the context is set up when
	 * the action handler is created, but the context can also be changed
	 * dynamically.
	 * 
	 * @param context 
	 *            the context to be used for the undo history
	 */
	public void setContext(IUndoContext context) {
		undoContext = context;
		update();
	}

	/**
	 * Specify whether the action handler should actively prune the operation
	 * history when invalid operations are encountered. The default value is
	 * <code>false</code>.
	 * 
	 * @param prune 
	 *            <code>true</code> if the history should be pruned by the
	 *            handler, and <code>false</code> if it should not.
	 * 
	 */
	public void setPruneHistory(boolean prune) {
		pruning = prune;
	}

	/**
	 * Update enabling and labels according to the current status of the
	 * operation history.
	 */
	public void update() {
		boolean enabled = shouldBeEnabled();
		String text = getCommandString();
		if (enabled) {
			text = NLS.bind(WorkbenchMessages.Operations_undoRedoCommand, text,
					shortenText(getOperation().getLabel()));
		} else {
			/*
			 * if there is nothing to do and we are pruning the history, flush
			 * the history of this context.
			 */
			if (undoContext != null && pruning)
				flush();
		}
		setText(text);
		setEnabled(enabled);
	}

	/*
	 * Shorten the specified command label if it is too long
	 */
	private String shortenText(String message) {
		int length = message.length();
		if (length > MAX_LABEL_LENGTH) {
			StringBuffer result = new StringBuffer();
			int mid = MAX_LABEL_LENGTH / 2;
			result.append(message.substring(0, mid));
			result.append("..."); //$NON-NLS-1$
			result.append(message.substring(length - mid));
			return result.toString();
		}
		return message;
	}

	/*
	 * Report the specified execution exception to the log and to the user.
	 */
	final void reportException(Exception e) {
		Throwable nestedException = e.getCause();
		Throwable exception = (nestedException == null) ? e : nestedException;
		String title = WorkbenchMessages.Error;
		String message = WorkbenchMessages.WorkbenchWindow_exceptionMessage;
		String exceptionMessage = exception.getMessage();
		if (exceptionMessage == null) {
			exceptionMessage = message;
		}
		IStatus status = new Status(IStatus.ERROR,
				WorkbenchPlugin.PI_WORKBENCH, 0, exceptionMessage, exception);
		WorkbenchPlugin.log(message, status);
		ErrorDialog.openError(getWorkbenchWindow().getShell(), title, message,
				status);
	}

}
