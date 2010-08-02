package uk.ac.manchester.cs.owl.owlapi.alternateimpls;

/*
 * Copyright (C) 2010, University of Manchester
 *
 * Modifications to the initial code base are copyright of their
 * respective authors, or their employers as appropriate.  Authorship
 * of the modifications may be determined from the ChangeLog placed at
 * the end of this file.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.

 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.

 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.io.IRIDocumentSource;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.OWLOntologyDocumentTarget;
import org.semanticweb.owlapi.io.OWLOntologyStorageIOException;
import org.semanticweb.owlapi.io.OntologyIRIMappingNotFoundException;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.DefaultChangeBroadcastStrategy;
import org.semanticweb.owlapi.model.DefaultImpendingChangeBroadcastStrategy;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.ImmutableOWLOntologyChangeException;
import org.semanticweb.owlapi.model.ImpendingOWLOntologyChangeBroadcastStrategy;
import org.semanticweb.owlapi.model.ImpendingOWLOntologyChangeListener;
import org.semanticweb.owlapi.model.MissingImportEvent;
import org.semanticweb.owlapi.model.MissingImportListener;
import org.semanticweb.owlapi.model.OWLAnnotationAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLMutableOntology;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyAlreadyExistsException;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeBroadcastStrategy;
import org.semanticweb.owlapi.model.OWLOntologyChangeException;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;
import org.semanticweb.owlapi.model.OWLOntologyChangeProgressListener;
import org.semanticweb.owlapi.model.OWLOntologyChangeVetoException;
import org.semanticweb.owlapi.model.OWLOntologyChangesVetoedListener;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyDocumentAlreadyExistsException;
import org.semanticweb.owlapi.model.OWLOntologyFactory;
import org.semanticweb.owlapi.model.OWLOntologyFactoryNotFoundException;
import org.semanticweb.owlapi.model.OWLOntologyFormat;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyIRIMappingNotFoundException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderListener;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyManagerProperties;
import org.semanticweb.owlapi.model.OWLOntologyRenameException;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.OWLOntologyStorer;
import org.semanticweb.owlapi.model.OWLOntologyStorerNotFoundException;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.SetOntologyID;
import org.semanticweb.owlapi.model.UnknownOWLOntologyException;
import org.semanticweb.owlapi.model.UnloadableImportException;
import org.semanticweb.owlapi.util.CollectionFactory;
import org.semanticweb.owlapi.util.NonMappingOntologyIRIMapper;

public class LockingOWLOntologyManagerImpl implements OWLOntologyManager,
		OWLOntologyFactory.OWLOntologyCreationHandler {
	private static final Logger logger = Logger
			.getLogger(LockingOWLOntologyManagerImpl.class.getName());
	private Map<OWLOntologyID, OWLOntology> ontologiesByID;
	private Map<OWLOntologyID, IRI> documentIRIsByID;
	private Map<OWLOntology, OWLOntologyFormat> ontologyFormatsByOntology;
	private Map<OWLImportsDeclaration, OWLOntologyID> ontologyIDsByImportsDeclaration;
	private List<OWLOntologyIRIMapper> documentMappers;
	private List<OWLOntologyFactory> ontologyFactories;
	private List<OWLOntologyStorer> ontologyStorers;
	private final Set broadcastChanges = CollectionFactory.createSyncSet();
	private int loadCount = 0;
	private int importsLoadCount = 0;
	private boolean silentMissingImportsHandling;
	private final OWLDataFactory dataFactory;
	private Map<OWLOntology, Set<OWLOntology>> importsClosureCache;
	private OWLOntologyManagerProperties properties;
	private List<MissingImportListener> missingImportsListeners;
	private List<OWLOntologyLoaderListener> loaderListeners;
	private List<OWLOntologyChangeProgressListener> progressListeners;
	private int autoGeneratedURICounter;
	private OWLOntologyChangeBroadcastStrategy defaultChangeBroadcastStrategy;
	private ImpendingOWLOntologyChangeBroadcastStrategy defaultImpendingChangeBroadcastStrategy;

	private <K, V> Map<K, V> createMap() {
		return new ConcurrentHashMap<K, V>();
	}

	private <V> List<V> createList() {
		return new CopyOnWriteArrayList<V>();
	}

	public LockingOWLOntologyManagerImpl(OWLDataFactory dataFactory) {
		this.dataFactory = dataFactory;
		properties = new OWLOntologyManagerProperties();
		ontologiesByID = createMap();
		documentIRIsByID = createMap();
		ontologyFormatsByOntology = createMap();
		documentMappers = createList();
		ontologyFactories = createList();
		ontologyIDsByImportsDeclaration = createMap();
		installDefaultURIMappers();
		installDefaultOntologyFactories();
		
		ontologyStorers = createList();
		importsClosureCache = createMap();
		missingImportsListeners = createList();
		loaderListeners = createList();
		progressListeners = createList();
		autoGeneratedURICounter = 0;
		defaultChangeBroadcastStrategy = new DefaultChangeBroadcastStrategy();
		defaultImpendingChangeBroadcastStrategy = new DefaultImpendingChangeBroadcastStrategy();
	}

	public OWLOntologyManagerProperties getProperties() {
		return properties;
	}

	public OWLDataFactory getOWLDataFactory() {
		return dataFactory;
	}

	public Set<OWLOntology> getOntologies() {
		return new HashSet<OWLOntology>(ontologiesByID.values());
	}

	public Set<OWLOntology> getOntologies(OWLAxiom axiom) {
		Set<OWLOntology> result = new HashSet<OWLOntology>(ontologiesByID
				.size());
		for (OWLOntology ont : getOntologies()) {
			if (ont.containsAxiom(axiom)) {
				result.add(ont);
			}
		}
		return result;
	}

	public boolean contains(OWLOntology ontology) {
		return ontologiesByID.containsValue(ontology);
	}

	public boolean contains(IRI ontologyIRI) {
		return contains(new OWLOntologyID(ontologyIRI));
	}

	public boolean contains(OWLOntologyID id) {
		return ontologiesByID.containsKey(id);
	}

	/**
	 * Gets a previously loaded/created ontology that has the specified ontology
	 * IRI and no version IRI.
	 * 
	 * @param ontologyIRI
	 *            The IRI of the ontology to be retrieved.
	 * @return The ontology that has the specified IRI and no version IRI, or
	 *         <code>null</code> if this manager does not manage an ontology
	 *         with the specified IRI and no version IRI.
	 */
	public OWLOntology getOntology(IRI ontologyIRI) {
		OWLOntologyID ontologyID = new OWLOntologyID(ontologyIRI);
		return getOntology(ontologyID);
	}

	/**
	 * Gets a previously loaded/created ontology that has the specified ontology
	 * ID
	 * 
	 * @param ontologyID
	 *            The ID of the ontology to retrieve
	 * @return The ontology that has the specified ID, or <code>null</code> if
	 *         this manager does not manage an ontology with the specified
	 *         ontology ID.
	 */
	public OWLOntology getOntology(OWLOntologyID ontologyID) {
		return ontologiesByID.get(ontologyID);
	}

	public Set<OWLOntology> getVersions(IRI ontology) {
		Set<OWLOntology> onts = new HashSet<OWLOntology>();
		for (OWLOntology ont : getOntologies()) {
			if (ontology.equals(ont.getOntologyID().getOntologyIRI())) {
				onts.add(ont);
			}
		}
		return onts;
	}

	/**
	 * Given an imports declaration, obtains the ontology that this import has
	 * been resolved to.
	 * 
	 * @param declaration
	 *            The declaration that points to the imported ontology.
	 * @return The ontology that the imports declaration resolves to, or
	 *         <code>null</code> if the imports declaration could not be
	 *         resolved to an ontology, because the ontology was not loaded or
	 *         has been removed from this manager
	 */
	public OWLOntology getImportedOntology(OWLImportsDeclaration declaration) {
		OWLOntologyID ontologyID = ontologyIDsByImportsDeclaration
				.get(declaration);
		if (ontologyID == null) {
			// No such ontology
			return null;
		} else {
			return getOntology(ontologyID);
		}
	}

	/**
	 * Gets the set of <em>loaded</em> ontologies that the specified ontology is
	 * related to via the directlyImports relation as defined in Section 3.4 of
	 * the OWL 2 Structural specification
	 * 
	 * @param ontology
	 *            The ontology whose direct imports are to be retrieved.
	 * @return The set of <em>loaded</em> ontologies that the specified ontology
	 *         is related to via the directlyImports relation.
	 * 
	 * @throws org.semanticweb.owlapi.model.UnknownOWLOntologyException
	 *             if there isn't an ontology in this manager which has the
	 *             specified IRI.
	 */
	public Set<OWLOntology> getDirectImports(OWLOntology ontology)
			throws UnknownOWLOntologyException {
		if (!contains(ontology)) {
			throw new UnknownOWLOntologyException(ontology.getOntologyID());
		}
		Set<OWLOntology> imports = new HashSet<OWLOntology>();
		for (OWLImportsDeclaration axiom : ontology.getImportsDeclarations()) {
			OWLOntology importedOntology = getImportedOntology(axiom);
			if (importedOntology != null) {
				imports.add(importedOntology);
			}
		}
		return imports;
	}

	/**
	 * Gets the set of ontologies that are in the transitive closure of the
	 * directly imports relation.
	 * 
	 * @param ontology
	 *            The ontology whose imports are to be retrieved.
	 * @return A set of <code>OWLOntology</code>ies that are in the transitive
	 *         closure of the directly imports relation of this ontology. If,
	 *         for what ever reason, an imported ontology could not be loaded,
	 *         then it will not be contained in the returned set of ontologies.
	 * 
	 * @throws org.semanticweb.owlapi.model.UnknownOWLOntologyException
	 *             if there isn't an ontology in this manager which has the
	 *             specified IRI.
	 */
	public Set<OWLOntology> getImports(OWLOntology ontology)
			throws UnknownOWLOntologyException {
		if (!contains(ontology)) {
			throw new UnknownOWLOntologyException(ontology.getOntologyID());
		}
		Set<OWLOntology> result = new HashSet<OWLOntology>();
		getImports(ontology, result);
		return result;
	}

	/**
	 * A method that gets the imports of a given ontology
	 * 
	 * @param ont
	 *            The ontology whose (transitive) imports are to be retrieved.
	 * @param result
	 *            A place to store the result - the transitive closure of the
	 *            imports will be stored in this result set.
	 */
	private void getImports(OWLOntology ont, Set<OWLOntology> result) {
		for (OWLOntology directImport : getDirectImports(ont)) {
			if (result.add(directImport)) {
				getImports(directImport, result);
			}
		}
	}

	public Set<OWLOntology> getImportsClosure(OWLOntology ontology) {
		Set<OWLOntology> ontologies = importsClosureCache.get(ontology);
		if (ontologies == null) {
			ontologies = new HashSet<OWLOntology>();
			getImportsClosure(ontology, ontologies);
			ontologies = Collections.unmodifiableSet(ontologies);
			importsClosureCache.put(ontology, ontologies);
		}
		return ontologies;
	}

	/**
	 * A recursive method that gets the reflexive transitive closure of the
	 * ontologies that are imported by this ontology.
	 * 
	 * @param ontology
	 *            The ontology whose reflexive transitive closure is to be
	 *            retrieved
	 * @param ontologies
	 *            a place to store the result
	 */
	private void getImportsClosure(OWLOntology ontology,
			Set<OWLOntology> ontologies) {
		ontologies.add(ontology);
		for (OWLOntology ont : getDirectImports(ontology)) {
			if (!ontologies.contains(ont)) {
				getImportsClosure(ont, ontologies);
			}
		}
	}

	public List<OWLOntology> getSortedImportsClosure(OWLOntology ontology)
			throws UnknownOWLOntologyException {
		List<OWLOntology> importsClosure = new ArrayList<OWLOntology>();
		getSortedImportsClosure(ontology, importsClosure,
				new HashSet<OWLOntology>());
		return importsClosure;
	}

	private void getSortedImportsClosure(OWLOntology ontology,
			List<OWLOntology> imports, Set<OWLOntology> marker) {
		if (!marker.contains(ontology)) {
			imports.add(ontology);
			marker.add(ontology);
			for (OWLOntology imported : getDirectImports(ontology)) {
				getSortedImportsClosure(imported, imports, marker);
			}
		}
	}

	/**
	 * Determines if a change is applicable. A change may not be applicable for
	 * a number of reasons.
	 * 
	 * @param change
	 *            The change to be tested.
	 * @return <code>true</code> if the change is applicable, otherwise,
	 *         <code>false</code>.
	 */
	private boolean isChangeApplicable(OWLOntologyChange change) {
		if (!getProperties().isLoadAnnotationAxioms()
				&& change instanceof AddAxiom) {
			if (change.getAxiom() instanceof OWLAnnotationAxiom) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Applies a change to an ontology and performs the necessary housekeeping
	 * tasks.
	 * 
	 * @param change
	 *            The change to be applied.
	 * @return A list of changes that were actually applied.
	 * 
	 * @throws OWLOntologyChangeException
	 */
	private List<OWLOntologyChange> enactChangeApplication(
			OWLOntologyChange change) {
		if (!isChangeApplicable(change)) {
			return Collections.emptyList();
		}
		OWLOntology ont = change.getOntology();
		if (!(ont instanceof OWLMutableOntology)) {
			throw new ImmutableOWLOntologyChangeException(change);
		}
		checkForOntologyIDChange(change);
		List<OWLOntologyChange> appliedChanges = ((OWLMutableOntology) ont)
				.applyChange(change);
		checkForImportsChange(change);
		return appliedChanges;
	}

	public List<OWLOntologyChange> applyChanges(
			List<? extends OWLOntologyChange> changes) {
		try {
			broadcastImpendingChanges(changes);
		} catch (OWLOntologyChangeVetoException e) {
			// Some listener blocked the changes.
			broadcastOntologyChangesVetoed(changes, e);
			return Collections.emptyList();
		}
		List<OWLOntologyChange> appliedChanges = new ArrayList<OWLOntologyChange>(
				changes.size() + 2);
		fireBeginChanges(changes.size());
		for (OWLOntologyChange change : changes) {
			appliedChanges.addAll(enactChangeApplication(change));
			fireChangeApplied(change);
		}
		fireEndChanges();
		broadcastChanges(changes);
		return appliedChanges;
	}

	public List<OWLOntologyChange> addAxiom(OWLOntology ont, OWLAxiom axiom) {
		return addAxioms(ont, Collections.singleton(axiom));
	}

	public List<OWLOntologyChange> addAxioms(OWLOntology ont,
			Set<? extends OWLAxiom> axioms) {
		List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>(
				axioms.size() + 2);
		for (OWLAxiom ax : axioms) {
			changes.add(new AddAxiom(ont, ax));
		}
		return applyChanges(changes);
	}

	public List<OWLOntologyChange> removeAxiom(OWLOntology ont, OWLAxiom axiom) {
		return removeAxioms(ont, Collections.singleton(axiom));
	}

	public List<OWLOntologyChange> removeAxioms(OWLOntology ont,
			Set<? extends OWLAxiom> axioms) {
		List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>(
				axioms.size() + 2);
		for (OWLAxiom ax : axioms) {
			changes.add(new RemoveAxiom(ont, ax));
			applyChanges(changes);
		}
		return changes;
	}

	public List<OWLOntologyChange> applyChange(OWLOntologyChange change) {
		return applyChanges(Arrays.asList(change));
	}

	private void checkForImportsChange(OWLOntologyChange change) {
		if (change.isImportChange()) {
			resetImportsClosureCache();
			if (change instanceof AddImport) {
				// Do we contain the import already?
				for (OWLOntologyID id : ontologiesByID.keySet()) {
					OWLImportsDeclaration addImportDeclaration = ((AddImport) change)
							.getImportDeclaration();
					if (id.getDefaultDocumentIRI() != null
							&& id.getDefaultDocumentIRI().equals(
									addImportDeclaration.getIRI())) {
						// Yes we do
						ontologyIDsByImportsDeclaration.put(
								addImportDeclaration, id);
					}
				}
			} else {
				// Remove the mapping from declaration to ontology
				ontologyIDsByImportsDeclaration.remove(((RemoveImport) change)
						.getImportDeclaration());
			}
		}
	}

	private void checkForOntologyIDChange(OWLOntologyChange change) {
		if (change instanceof SetOntologyID) {
			SetOntologyID setID = (SetOntologyID) change;
			OWLOntology existingOntology = ontologiesByID
					.get(((SetOntologyID) change).getNewOntologyID());
			if (existingOntology != null
					&& !change.getOntology().equals(existingOntology)) {
				throw new OWLOntologyRenameException(change,
						((SetOntologyID) change).getNewOntologyID());
			}
			renameOntology(setID.getOriginalOntologyID(), setID
					.getNewOntologyID());
			resetImportsClosureCache();
		}
	}

	/////////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Methods to create, load and reload ontologies
	//
	/////////////////////////////////////////////////////////////////////////////////////////////////////////////
	public void ontologyCreated(OWLOntology ontology) {
		// This method is called when a factory that we have asked to create or
		// load an ontology has created the ontology.  We add the ontology to the
		// set of loaded ontologies.
		addOntology(ontology);
	}

	/**
	 * Sets the format of an ontology
	 * 
	 * @param ontology
	 *            The ontology
	 * @param format
	 *            The format of the ontology
	 */
	public void setOntologyFormat(OWLOntology ontology, OWLOntologyFormat format) {
		ontologyFormatsByOntology.put(ontology, format);
	}

	public OWLOntologyFormat getOntologyFormat(OWLOntology ontology) {
		return ontologyFormatsByOntology.get(ontology);
	}

	public OWLOntology createOntology() throws OWLOntologyCreationException {
		// Brand new ontology without a URI
		return createOntology(new OWLOntologyID());
	}

	public OWLOntology createOntology(IRI ontologyIRI)
			throws OWLOntologyCreationException {
		return createOntology(new OWLOntologyID(ontologyIRI));
	}

	public OWLOntology createOntology(IRI ontologyIRI, IRI versionIRI)
			throws OWLOntologyCreationException {
		return createOntology(new OWLOntologyID(ontologyIRI, versionIRI));
	}

	public OWLOntology createOntology(OWLOntologyID ontologyID)
			throws OWLOntologyCreationException {
		OWLOntology ontology = ontologiesByID.get(ontologyID);
		if (ontology != null) {
			throw new OWLOntologyAlreadyExistsException(ontologyID);
		}
		IRI documentIRI = getDocumentIRIFromMappers(ontologyID, false);
		if (documentIRI == null) {
			documentIRI = IRI.generateDocumentIRI();
			Collection<IRI> existingDocumentIRIs = documentIRIsByID.values();
			while (existingDocumentIRIs.contains(documentIRI)) {
				documentIRI = IRI.generateDocumentIRI();
			}
		}
		if (documentIRIsByID.values().contains(documentIRI)) {
			throw new OWLOntologyDocumentAlreadyExistsException(documentIRI);
		}
		for (OWLOntologyFactory factory : ontologyFactories) {
			if (factory.canCreateFromDocumentIRI(documentIRI)) {
				documentIRIsByID.put(ontologyID, documentIRI);
				return factory.createOWLOntology(ontologyID, documentIRI, this);
			}
		}
		throw new OWLOntologyFactoryNotFoundException(documentIRI);
	}

	public OWLOntology createOntology(IRI ontologyIRI,
			Set<OWLOntology> ontologies) throws OWLOntologyCreationException {
		return createOntology(ontologyIRI, ontologies, false);
	}

	public OWLOntology createOntology(IRI ontologyIRI,
			Set<OWLOntology> ontologies, boolean copyLogicalAxiomsOnly)
			throws OWLOntologyCreationException {
		if (contains(ontologyIRI)) {
			throw new OWLOntologyAlreadyExistsException(new OWLOntologyID(
					ontologyIRI));
		}
		OWLOntology ont = createOntology(ontologyIRI);
		Set<OWLAxiom> axioms = new HashSet<OWLAxiom>();
		for (OWLOntology ontology : ontologies) {
			if (copyLogicalAxiomsOnly) {
				axioms.addAll(ontology.getLogicalAxioms());
			} else {
				axioms.addAll(ontology.getAxioms());
			}
		}
		addAxioms(ont, axioms);
		return ont;
	}

	public OWLOntology createOntology(Set<OWLAxiom> axioms, IRI iri)
			throws OWLOntologyCreationException {
		if (contains(iri)) {
			throw new OWLOntologyAlreadyExistsException(new OWLOntologyID(iri));
		}
		OWLOntology ont = createOntology(iri);
		addAxioms(ont, axioms);
		return ont;
	}

	public OWLOntology createOntology(Set<OWLAxiom> axioms)
			throws OWLOntologyCreationException {
		return createOntology(axioms, getNextAutoGeneratedIRI());
	}

	protected synchronized IRI getNextAutoGeneratedIRI() {
		autoGeneratedURICounter = autoGeneratedURICounter + 1;
		return IRI.create("owlapi:ontology:ont" + autoGeneratedURICounter);
	}

	public OWLOntology loadOntology(IRI ontologyIRI)
			throws OWLOntologyCreationException {
		return loadOntology(ontologyIRI, false);
	}

	private OWLOntology loadOntology(IRI ontologyIRI, boolean allowExists)
			throws OWLOntologyCreationException {
		OWLOntologyID id = new OWLOntologyID(ontologyIRI);
		OWLOntology ontByID = ontologiesByID.get(id);
		if (ontByID != null) {
			return ontByID;
		}
		IRI documentIRI = getDocumentIRIFromMappers(id, true);
		if (documentIRIsByID.values().contains(documentIRI) && !allowExists) {
			throw new OWLOntologyDocumentAlreadyExistsException(documentIRI);
		}
		// The ontology might be being loaded, but its IRI might
		// not have been set (as is probably the case with RDF/XML!)
		if (documentIRI != null) {
			OWLOntology ontByDocumentIRI = getOntologyByDocumentIRI(documentIRI);
			if (ontByDocumentIRI != null) {
				return ontByDocumentIRI;
			}
		} else {
			// Nothing we can do here.  We can't get a document IRI to load
			// the ontology from.
			throw new OntologyIRIMappingNotFoundException(ontologyIRI);
		}
		return loadOntology(ontologyIRI, new IRIDocumentSource(documentIRI));
	}

	private OWLOntology getOntologyByDocumentIRI(IRI documentIRI) {
		for (OWLOntologyID ontID : documentIRIsByID.keySet()) {
			IRI docIRI = documentIRIsByID.get(ontID);
			if (docIRI != null && docIRI.equals(documentIRI)) {
				return getOntology(ontID);
			}
		}
		return null;
	}

	public OWLOntology loadOntologyFromOntologyDocument(IRI documentIRI)
			throws OWLOntologyCreationException {
		// Ontology URI not known in advance
		return loadOntology(null, new IRIDocumentSource(documentIRI));
	}

	public OWLOntology loadOntologyFromOntologyDocument(
			OWLOntologyDocumentSource documentSource)
			throws OWLOntologyCreationException {
		// Ontology URI not known in advance
		return loadOntology(null, documentSource);
	}

	public OWLOntology loadOntologyFromOntologyDocument(File file)
			throws OWLOntologyCreationException {
		return loadOntologyFromOntologyDocument(new FileDocumentSource(file));
	}

	public OWLOntology loadOntologyFromOntologyDocument(InputStream inputStream)
			throws OWLOntologyCreationException {
		return loadOntologyFromOntologyDocument(new StreamDocumentSource(
				inputStream));
	}

	/**
	 * This is the method that all the other load method delegate to.
	 * 
	 * @param ontologyIRI
	 *            The URI of the ontology to be loaded. This is only used to
	 *            report to listeners and may be <code>null</code>
	 * @param documentSource
	 *            The input source that specifies where the ontology should be
	 *            loaded from.
	 * @return The ontology that was loaded.
	 * 
	 * @throws OWLOntologyCreationException
	 *             If the ontology could not be loaded.
	 */
	protected OWLOntology loadOntology(IRI ontologyIRI,
			OWLOntologyDocumentSource documentSource)
			throws OWLOntologyCreationException {
		Object loadKey=new Object();
		if (loadCount != importsLoadCount) {
			System.err
					.println("Runtime Warning: Parsers should load imported ontologies using the makeImportLoadRequest method.");
		}
		fireStartedLoadingEvent(new OWLOntologyID(ontologyIRI), documentSource
				.getDocumentIRI(), loadCount > 0);
		synchronized (this) {
			loadCount++;
		}
		broadcastChanges.add(loadKey);
		OWLOntologyCreationException ex = null;
		OWLOntologyID idOfLoadedOntology = new OWLOntologyID();
		try {
			for (OWLOntologyFactory factory : ontologyFactories) {
				if (factory.canLoad(documentSource)) {
					OWLOntology ontology = null;
					try {
						// Note - there is no need to add the ontology here, because it will be added
						// when the ontology is created.
						ontology = factory
								.loadOWLOntology(documentSource, this);
						idOfLoadedOntology = ontology.getOntologyID();
						// Store the ontology to the document IRI mapping
						documentIRIsByID.put(ontology.getOntologyID(),
								documentSource.getDocumentIRI());
						return ontology;
					} catch (OWLOntologyRenameException e) {
						// We loaded an ontology from a document and the ontology turned out to have an IRI the same
						// as a previously loaded ontology
						throw new OWLOntologyAlreadyExistsException(e
								.getOntologyID());
					}
				}
			}
		} catch (OWLOntologyCreationException e) {
			ex = e;
			throw e;
		} finally {
			synchronized (this) {
				loadCount--;
			}
			// the current load key
			//if (loadCount == 0) {
				broadcastChanges.remove(loadKey);
				//                 Completed loading ontology and imports
			//}
			fireFinishedLoadingEvent(idOfLoadedOntology, documentSource
					.getDocumentIRI(), loadCount > 0, ex);
		}
		throw new OWLOntologyFactoryNotFoundException(documentSource
				.getDocumentIRI());
	}

	public void removeOntology(OWLOntology ontology) {
		ontologiesByID.remove(ontology.getOntologyID());
		ontologyFormatsByOntology.remove(ontology);
		documentIRIsByID.remove(ontology.getOntologyID());
		for (Iterator<OWLImportsDeclaration> it = ontologyIDsByImportsDeclaration
				.keySet().iterator(); it.hasNext();) {
			if (ontologyIDsByImportsDeclaration.get(it.next()).equals(
					ontology.getOntologyID())) {
				it.remove();
			}
		}
		resetImportsClosureCache();
	}

	private void addOntology(OWLOntology ont) {
		ontologiesByID.put(ont.getOntologyID(), ont);
	}

	public IRI getOntologyDocumentIRI(OWLOntology ontology)
			throws UnknownOWLOntologyException {
		if (!contains(ontology)) {
			throw new UnknownOWLOntologyException(ontology.getOntologyID());
		}
		return documentIRIsByID.get(ontology.getOntologyID());
	}

	public void setOntologyDocumentIRI(OWLOntology ontology, IRI documentIRI)
			throws UnknownOWLOntologyException {
		if (!ontologiesByID.containsKey(ontology.getOntologyID())) {
			throw new UnknownOWLOntologyException(ontology.getOntologyID());
		}
		documentIRIsByID.put(ontology.getOntologyID(), documentIRI);
	}

	/**
	 * Handles a rename of an ontology. This method should only be called
	 * *after* the change has been applied
	 * 
	 * @param oldID
	 *            The original ID of the ontology
	 */
	private void renameOntology(OWLOntologyID oldID, OWLOntologyID newID) {
		OWLOntology ont = ontologiesByID.get(oldID);
		if (ont == null) {
			// Nothing to rename!
			return;
		}
		ontologiesByID.remove(oldID);
		ontologiesByID.put(newID, ont);
		IRI documentIRI = documentIRIsByID.remove(oldID);
		if (documentIRI != null) {
			documentIRIsByID.put(newID, documentIRI);
		}
		resetImportsClosureCache();
	}

	private void resetImportsClosureCache() {
		importsClosureCache.clear();
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Methods to save ontologies
	//
	///////////////////////////////////////////////////////////////////////////////////////////////////////////
	public void saveOntology(OWLOntology ontology)
			throws OWLOntologyStorageException, UnknownOWLOntologyException {
		OWLOntologyFormat format = getOntologyFormat(ontology);
		saveOntology(ontology, format);
	}

	public void saveOntology(OWLOntology ontology,
			OWLOntologyFormat ontologyFormat)
			throws OWLOntologyStorageException, UnknownOWLOntologyException {
		IRI documentIRI = getOntologyDocumentIRI(ontology);
		saveOntology(ontology, ontologyFormat, documentIRI);
	}

	public void saveOntology(OWLOntology ontology, IRI documentIRI)
			throws OWLOntologyStorageException, UnknownOWLOntologyException {
		OWLOntologyFormat format = getOntologyFormat(ontology);
		saveOntology(ontology, format, documentIRI);
	}

	public void saveOntology(OWLOntology ontology,
			OWLOntologyFormat ontologyFormat, IRI documentIRI)
			throws OWLOntologyStorageException, UnknownOWLOntologyException {
		try {
			for (OWLOntologyStorer storer : ontologyStorers) {
				if (storer.canStoreOntology(ontologyFormat)) {
					storer.storeOntology(this, ontology, documentIRI,
							ontologyFormat);
					return;
				}
			}
			throw new OWLOntologyStorerNotFoundException(ontologyFormat);
		} catch (IOException e) {
			throw new OWLOntologyStorageIOException(e);
		}
	}

	public void saveOntology(OWLOntology ontology, OutputStream outputStream)
			throws OWLOntologyStorageException {
		saveOntology(ontology, new StreamDocumentTarget(outputStream));
	}

	public void saveOntology(OWLOntology ontology,
			OWLOntologyFormat ontologyFormat, OutputStream outputStream)
			throws OWLOntologyStorageException {
		saveOntology(ontology, ontologyFormat, new StreamDocumentTarget(
				outputStream));
	}

	public void saveOntology(OWLOntology ontology,
			OWLOntologyDocumentTarget documentTarget)
			throws OWLOntologyStorageException, UnknownOWLOntologyException {
		saveOntology(ontology, getOntologyFormat(ontology), documentTarget);
	}

	public void saveOntology(OWLOntology ontology,
			OWLOntologyFormat ontologyFormat,
			OWLOntologyDocumentTarget documentTarget)
			throws OWLOntologyStorageException, UnknownOWLOntologyException {
		try {
			for (OWLOntologyStorer storer : ontologyStorers) {
				if (storer.canStoreOntology(ontologyFormat)) {
					storer.storeOntology(this, ontology, documentTarget,
							ontologyFormat);
					return;
				}
			}
			throw new OWLOntologyStorerNotFoundException(ontologyFormat);
		} catch (IOException e) {
			throw new OWLOntologyStorageIOException(e);
		}
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Methods to add/remove ontology storers
	//
	///////////////////////////////////////////////////////////////////////////////////////////////////////////
	public void addOntologyStorer(OWLOntologyStorer storer) {
		ontologyStorers.add(0, storer);
	}

	public void removeOntologyStorer(OWLOntologyStorer storer) {
		ontologyStorers.remove(storer);
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Methods to add/remove mappers etc.
	//
	///////////////////////////////////////////////////////////////////////////////////////////////////////////
	public void addIRIMapper(OWLOntologyIRIMapper mapper) {
		documentMappers.add(0, mapper);
	}

	public void clearIRIMappers() {
		documentMappers.clear();
	}

	public void removeIRIMapper(OWLOntologyIRIMapper mapper) {
		documentMappers.remove(mapper);
	}

	public void addOntologyFactory(OWLOntologyFactory factory) {
		ontologyFactories.add(0, factory);
		factory.setOWLOntologyManager(this);
	}

	public void removeOntologyFactory(OWLOntologyFactory factory) {
		ontologyFactories.remove(factory);
	}

	/**
	 * Uses the mapper mechanism to obtain an ontology document IRI from an
	 * ontology IRI.
	 * 
	 * @param ontologyID
	 *            The ontology ID for which a document IRI is to be retrieved
	 * @param quiet
	 *            If set to <code>true</code> and a mapping can't be found then
	 *            a value of <code>null</code> is returned. If set to
	 *            <code>false</code> and a mapping can't be found then an
	 *            exception
	 *            {@link org.semanticweb.owlapi.model.OWLOntologyIRIMappingNotFoundException}
	 *            is thrown.
	 * @return The document IRI that corresponds to the ontology IRI, or
	 *         <code>null</code> if no physical URI can be found.
	 */
	private IRI getDocumentIRIFromMappers(OWLOntologyID ontologyID,
			boolean quiet) {
		IRI defIRI = ontologyID.getDefaultDocumentIRI();
		if (defIRI == null) {
			return null;
		}
		for (OWLOntologyIRIMapper mapper : documentMappers) {
			IRI documentIRI = mapper.getDocumentIRI(defIRI);
			if (documentIRI != null) {
				return documentIRI;
			}
		}
		if (!quiet) {
			throw new OWLOntologyIRIMappingNotFoundException(ontologyID
					.getDefaultDocumentIRI());
		} else {
			return null;
		}
	}

	private void installDefaultURIMappers() {
		// By defaut install the default mapper that simply maps
		// ontology URIs to themselves.
		addIRIMapper(new NonMappingOntologyIRIMapper());
	}

	private void installDefaultOntologyFactories() {
		// The default factories are the ones that can load
		// ontologies from http:// and file:// URIs
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Listener stuff - methods to add/remove listeners
	//
	////////////////////////////////////////////////////////////////////////////////////////////////////
	private Map<OWLOntologyChangeListener, OWLOntologyChangeBroadcastStrategy> listenerMap = new IdentityHashMap<OWLOntologyChangeListener, OWLOntologyChangeBroadcastStrategy>();
	private Lock listenerLock = new ReentrantLock();
	private Map<ImpendingOWLOntologyChangeListener, ImpendingOWLOntologyChangeBroadcastStrategy> impendingChangeListenerMap = new IdentityHashMap<ImpendingOWLOntologyChangeListener, ImpendingOWLOntologyChangeBroadcastStrategy>();
	private Lock impendingLock = new ReentrantLock();
	private List<OWLOntologyChangesVetoedListener> vetoListeners = createList();

	public void addOntologyChangeListener(OWLOntologyChangeListener listener) {
		listenerLock.lock();
		try {
			listenerMap.put(listener, defaultChangeBroadcastStrategy);
		} finally {
			listenerLock.unlock();
		}
	}

	/**
	 * Broadcasts to attached listeners, using the various broadcasting
	 * strategies that were specified for each listener.
	 * 
	 * @param changes
	 *            The ontology changes to broadcast
	 */
	private void broadcastChanges(List<? extends OWLOntologyChange> changes) {
		if (!broadcastChanges.isEmpty()) {
			return;
		}
		List<Map.Entry<OWLOntologyChangeListener, OWLOntologyChangeBroadcastStrategy>> list = null;
		listenerLock.lock();
		try {
			list = new ArrayList<Map.Entry<OWLOntologyChangeListener, OWLOntologyChangeBroadcastStrategy>>(
					listenerMap.entrySet());
		} finally {
			listenerLock.unlock();
		}
		for (Map.Entry<OWLOntologyChangeListener, OWLOntologyChangeBroadcastStrategy> e : list) {
			if (e.getValue() != null) {
				try {
					// Handle exceptions on a per listener basis.  If we have
					// badly behaving listeners, we don't want one listener
					// to prevent the other listeners from receiving events.
					e.getValue().broadcastChanges(e.getKey(), changes);
				} catch (Throwable ex) {
					logger.warning("BADLY BEHAVING LISTENER: " + ex);
					ex.printStackTrace();
				}
			}
		}
	}

	private void broadcastImpendingChanges(
			List<? extends OWLOntologyChange> changes)
			throws OWLOntologyChangeVetoException {
		if (!broadcastChanges.isEmpty()) {
			return;
		}
		List<Map.Entry<ImpendingOWLOntologyChangeListener, ImpendingOWLOntologyChangeBroadcastStrategy>> listeners = null;
		impendingLock.lock();
		try {
			listeners = new ArrayList<Map.Entry<ImpendingOWLOntologyChangeListener, ImpendingOWLOntologyChangeBroadcastStrategy>>(
					impendingChangeListenerMap.entrySet());
		} finally {
			impendingLock.unlock();
		}
		for (Map.Entry<ImpendingOWLOntologyChangeListener, ImpendingOWLOntologyChangeBroadcastStrategy> listener : listeners) {
			if (listener.getValue() != null) {
				listener.getValue()
						.broadcastChanges(listener.getKey(), changes);
			}
		}
	}

	public void setDefaultChangeBroadcastStrategy(
			OWLOntologyChangeBroadcastStrategy strategy) {
		if (strategy != null) {
			defaultChangeBroadcastStrategy = strategy;
		} else {
			defaultChangeBroadcastStrategy = new DefaultChangeBroadcastStrategy();
		}
	}

	public void addOntologyChangeListener(OWLOntologyChangeListener listener,
			OWLOntologyChangeBroadcastStrategy strategy) {
		listenerLock.lock();
		try {
			listenerMap.put(listener, strategy);
		} finally {
			listenerLock.unlock();
		}
	}

	public void addImpendingOntologyChangeListener(
			ImpendingOWLOntologyChangeListener listener) {
		impendingLock.lock();
		try {
			impendingChangeListenerMap.put(listener,
					defaultImpendingChangeBroadcastStrategy);
		} finally {
			impendingLock.unlock();
		}
	}

	public void removeImpendingOntologyChangeListener(
			ImpendingOWLOntologyChangeListener listener) {
		impendingLock.lock();
		try {
			impendingChangeListenerMap.remove(listener);
		} finally {
			impendingLock.unlock();
		}
	}

	public void removeOntologyChangeListener(OWLOntologyChangeListener listener) {
		listenerLock.lock();
		try {
			listenerMap.remove(listener);
		} finally {
			listenerLock.unlock();
		}
	}

	public void addOntologyChangesVetoedListener(
			OWLOntologyChangesVetoedListener listener) {
		vetoListeners.add(listener);
	}

	public void removeOntologyChangesVetoedListener(
			OWLOntologyChangesVetoedListener listener) {
		vetoListeners.remove(listener);
	}

	private void broadcastOntologyChangesVetoed(
			List<? extends OWLOntologyChange> changes,
			OWLOntologyChangeVetoException veto) {
		for (OWLOntologyChangesVetoedListener listener : new ArrayList<OWLOntologyChangesVetoedListener>(
				vetoListeners)) {
			listener.ontologyChangesVetoed(changes, veto);
		}
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	//  Imports etc.
	//
	////////////////////////////////////////////////////////////////////////////////////////////////////////////
	private OWLOntology loadImports(OWLImportsDeclaration declaration)
			throws OWLOntologyCreationException {
		synchronized (this) {
			importsLoadCount++;
		}
		OWLOntology ont = null;
		try {
			ont = loadOntology(declaration.getIRI(), true);
		} catch (OWLOntologyCreationException e) {
			if (!silentMissingImportsHandling) {
				throw e;
			} else {
				// Silent
				MissingImportEvent evt = new MissingImportEvent(declaration
						.getURI(), e);
				fireMissingImportEvent(evt);
			}
		} finally {
			synchronized (this) {
				importsLoadCount--;
			}
		}
		return ont;
	}

	public void makeLoadImportRequest(OWLImportsDeclaration declaration)
			throws UnloadableImportException {
		try {
			OWLOntology ont = loadImports(declaration);
			if (ont != null) {
				ontologyIDsByImportsDeclaration.put(declaration, ont
						.getOntologyID());
			}
		} catch (OWLOntologyCreationException e) {
			// Wrap as UnloadableImportException and throw
			throw new UnloadableImportException(e, declaration);
		}
	}

	public void setSilentMissingImportsHandling(boolean b) {
		silentMissingImportsHandling = b;
	}

	public boolean isSilentMissingImportsHandling() {
		return silentMissingImportsHandling;
	}

	public void addMissingImportListener(MissingImportListener listener) {
		missingImportsListeners.add(listener);
	}

	public void removeMissingImportListener(MissingImportListener listener) {
		missingImportsListeners.remove(listener);
	}

	protected void fireMissingImportEvent(MissingImportEvent evt) {
		for (MissingImportListener listener : new ArrayList<MissingImportListener>(
				missingImportsListeners)) {
			listener.importMissing(evt);
		}
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	//  Other listeners etc.
	//
	////////////////////////////////////////////////////////////////////////////////////////////////////////////
	public void addOntologyLoaderListener(OWLOntologyLoaderListener listener) {
		loaderListeners.add(listener);
	}

	public void removeOntologyLoaderListener(OWLOntologyLoaderListener listener) {
		loaderListeners.remove(listener);
	}

	protected void fireStartedLoadingEvent(OWLOntologyID ontologyID,
			IRI documentIRI, boolean imported) {
		for (OWLOntologyLoaderListener listener : new ArrayList<OWLOntologyLoaderListener>(
				loaderListeners)) {
			listener.startedLoadingOntology(new OWLOntologyLoaderListener.LoadingStartedEvent(
					ontologyID, documentIRI, imported));
		}
	}

	protected void fireFinishedLoadingEvent(OWLOntologyID ontologyID,
			IRI documentIRI, boolean imported, OWLOntologyCreationException ex) {
		for (OWLOntologyLoaderListener listener : new ArrayList<OWLOntologyLoaderListener>(
				loaderListeners)) {
			listener.finishedLoadingOntology(new OWLOntologyLoaderListener.LoadingFinishedEvent(
					ontologyID, documentIRI, imported, ex));
		}
	}

	public void addOntologyChangeProgessListener(
			OWLOntologyChangeProgressListener listener) {
		progressListeners.add(listener);
	}

	public void removeOntologyChangeProgessListener(
			OWLOntologyChangeProgressListener listener) {
		progressListeners.remove(listener);
	}

	protected void fireBeginChanges(int size) {
		try {
			if (!broadcastChanges.isEmpty()) {
				return;
			}
			for (OWLOntologyChangeProgressListener lsnr : progressListeners) {
				lsnr.begin(size);
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	protected void fireEndChanges() {
		try {
			if (!broadcastChanges.isEmpty()) {
				return;
			}
			for (OWLOntologyChangeProgressListener lsnr : progressListeners) {
				lsnr.end();
			}
		} catch (Throwable e) {
			// Listener threw an exception
			e.printStackTrace();
		}
	}

	protected void fireChangeApplied(OWLOntologyChange change) {
		try {
			if (!broadcastChanges.isEmpty()) {
				return;
			}
			if (progressListeners.isEmpty()) {
				return;
			}
			for (OWLOntologyChangeProgressListener lsnr : progressListeners) {
				lsnr.appliedChange(change);
			}
		} catch (Throwable e) {
			// Listener threw an exception
			e.printStackTrace();
		}
	}
}