/**********************************************************************
Copyright (c) 2000, 2003 IBM Corp. and others.
All rights reserved. This program and the accompanying materials
are made available under the terms of the Common Public License v1.0
which accompanies this distribution, and is available at
http://www.eclipse.org/legal/cpl-v10.html

Contributors:
	IBM Corporation - Initial implementation
**********************************************************************/
package org.eclipse.core.filebuffers;

import org.eclipse.jface.text.IDocument;

/**
 * Participates in the setup of a document.
 * @since 3.0
 */
public interface IDocumentSetupParticipant {
	
	/**
	 * Sets up the document to be ready for usage.
	 * 
	 * @param document the document to be set up
	 */
	void setup(IDocument document);
}
