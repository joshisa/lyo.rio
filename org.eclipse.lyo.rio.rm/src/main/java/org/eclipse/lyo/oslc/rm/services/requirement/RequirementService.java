/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompanies this distribution. 
 *
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at 
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *
 *    Jim Conallen - initial API and implementation
 *******************************************************************************/
package org.eclipse.lyo.oslc.rm.services.requirement;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.lyo.oslc.rm.common.IRmConstants;
import org.eclipse.lyo.rio.core.IConstants;
import org.eclipse.lyo.rio.l10n.Messages;
import org.eclipse.lyo.rio.query.PName;
import org.eclipse.lyo.rio.query.SimpleQueryBuilder;
import org.eclipse.lyo.rio.services.RioBaseService;
import org.eclipse.lyo.rio.services.RioServiceException;
import org.eclipse.lyo.rio.store.JsonFormatter2;
import org.eclipse.lyo.rio.store.JsonFormatter2.IMultiValueResolver;
import org.eclipse.lyo.rio.store.OslcResource;
import org.eclipse.lyo.rio.store.RioServerException;
import org.eclipse.lyo.rio.store.RioStatement;
import org.eclipse.lyo.rio.store.RioStore;
import org.eclipse.lyo.rio.store.RioValue;
import org.eclipse.lyo.rio.store.XmlFormatter;
import org.eclipse.lyo.rio.util.StringUtils;
import org.eclipse.lyo.rio.util.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;


/**
 * Servlet implementation class Resource
 */
public class RequirementService extends RioBaseService {

	private static final long serialVersionUID = -5280734755943517104L;

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			RioStore store = this.getStore();

			URI reqUri = URI.create(request.getRequestURI());
			String path = reqUri.getPath();
			StringTokenizer st = new StringTokenizer(path,"/");

			if( st.countTokens() > 2 ) {  // /rio-rm/requirement/{reqId}
				OslcResource resource = store.getOslcResource(request.getRequestURL().toString());
				if( resource == null ) {
					throw new RioServiceException(IConstants.SC_NOT_FOUND);
				}
				String accept = request.getHeader(IConstants.HDR_ACCEPT);
				if( this.willAccept(IConstants.CT_HTML, request) ) {
					Requirement amResource = new Requirement(resource);
					request.setAttribute("requirement", amResource); //$NON-NLS-1$
					RequestDispatcher rd = request.getRequestDispatcher("/rm/requirement_view.jsp"); //$NON-NLS-1$
					rd.forward(request, response);
				} else if( this.willAccept(IConstants.CT_APP_N_TRIPLES, request) ) {
					response.setContentType(IConstants.CT_JSON); 
					response.getWriter().write(resource.dumpNTriples()); 
					response.setStatus(IConstants.SC_OK);
				} else if( this.willAccept(IConstants.CT_JSON, request) ) {
					JsonFormatter2 formatter = new JsonFormatter2(new IMultiValueResolver() {
						@Override
						public boolean isMultiValued(String property) {
							if( IConstants.DCTERMS_CREATOR.equals(property) || 
									IConstants.DCTERMS_CONTRIBUTOR.equals(property) ||
									IConstants.DCTERMS_SUBJECT.equals(property) ||
									IRmConstants.OSLC_RM_ELABORATEDBY.equals(property) ||
									IRmConstants.OSLC_RM_SPECIFIEDBY.equals(property) ||
									IRmConstants.OSLC_RM_AFFECTEDBY.equals(property) ||
									IRmConstants.OSLC_RM_TRACKEDBY.equals(property) ||
									IRmConstants.OSLC_RM_IMPLEMENTEDBY.equals(property) ||
									IRmConstants.OSLC_RM_VALIDATEDBY.equals(property) ||
									IConstants.OSLC_SERVICEPROVIDER.equals(property) ) {
								return true;
							}  
							return false;
						}
					});
					formatter.addNamespacePrefix(IRmConstants.OSLC_RM_NAMESPACE, IRmConstants.OSLC_RM_PREFIX);
					String json = formatter.format(resource);
					response.setContentType(IConstants.CT_JSON); 
					response.getWriter().write(json); 
					response.setStatus(IConstants.SC_OK);
				} else if( accept == null || this.willAccept(IConstants.CT_RDF_XML, request) || this.willAccept(IConstants.CT_XML, request) ) {
					String content = XmlFormatter.formatResource(resource, IRmConstants.OSLC_RM_TYPE_REQUIREMENT); 
					response.setHeader(IConstants.HDR_ETAG, resource.getETag());
					String lm = StringUtils.rfc2822(resource.getModified());
					response.setHeader(IConstants.HDR_LAST_MODIFIED, lm);
					response.setStatus(IConstants.SC_OK);
					response.setContentType(IConstants.CT_RDF_XML);
					response.setContentLength(content.getBytes().length);
					response.getWriter().write(content);
				} else  if( this.willAccept(IConstants.CT_OSLC_COMPACT, request) ) {
					String content = compactDocument(resource);
					response.setContentType(IConstants.CT_OSLC_COMPACT);
					response.setContentLength(content.getBytes().length);
					response.setStatus(IConstants.SC_OK);
					response.getWriter().write(content);
				} else {
					throw new RioServiceException(IConstants.SC_UNSUPPORTED_MEDIA_TYPE, Messages.getString("Resource.UnableToAccept") + accept ); //$NON-NLS-1$
				}
			} else {
				processQuery(request, response);
			}
			
		} catch (RioServerException e) {
			throw new RioServiceException(IConstants.SC_INTERNAL_ERROR, e);
		}
	}

	/**
	 * @see HttpServlet#doPut(HttpServletRequest, HttpServletResponse)
	 */
	protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			RioStore store = this.getStore();
			OslcResource resource = store.getOslcResource(request.getRequestURL().toString());
			if( resource == null ) {
				throw new RioServiceException(IConstants.SC_NOT_FOUND);
			}
			
			checkConditionalHeaders(request, resource);

			// cache the created and creator
			Date created = resource.getCreated();
			String creator = resource.getCreator();
			
			// ok, then we update this resource
			String contentType = request.getContentType();
			if( !contentType.startsWith(IConstants.CT_RDF_XML) ) {
				throw new RioServiceException(IConstants.SC_UNSUPPORTED_MEDIA_TYPE);
			}
			
			ServletInputStream content = request.getInputStream();
			OslcResource updatedResource = new OslcResource(resource.getUri());
			List<RioStatement> statements = store.parse(resource.getUri(), content, contentType);
			updatedResource.addStatements(statements);
			updatedResource.setCreated(created);
			updatedResource.setCreator(creator);
			String userId = request.getRemoteUser();
			String userUri = this.getUserUri(userId);
			store.update(updatedResource, userUri);
			
			updatedResource = store.getOslcResource(resource.getUri());
			response.setStatus(IConstants.SC_OK);
			response.addHeader(IConstants.HDR_ETAG, updatedResource.getETag());
			response.addHeader(IConstants.HDR_LOCATION, updatedResource.getUri());
			String lastModified = StringUtils.rfc2822(updatedResource.getModified());
			response.addHeader(IConstants.HDR_LAST_MODIFIED, lastModified);
			
		} catch (RioServerException e) {
			throw new RioServiceException(IConstants.SC_BAD, e);
		}
	}

	/**
	 * @see HttpServlet#doDelete(HttpServletRequest, HttpServletResponse)
	 */
	protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			RioStore store = this.getStore();
			OslcResource resource = store.getOslcResource(request.getRequestURL().toString());
			if( resource == null ) {
				throw new RioServiceException(IConstants.SC_NOT_FOUND);
			}
			checkConditionalHeaders(request, resource);
			store.remove(resource); 
		} catch (RioServerException e) {
			throw new RioServiceException(IConstants.SC_INTERNAL_ERROR, e);
		}
	}

	@SuppressWarnings("nls")
	private String compactDocument(OslcResource resource ) throws RioServiceException {
		String title = XmlUtils.encode(resource.getTitle());
		String shortTitle = "Resource " + resource.getIdentifier();
		String resourceUri = resource.getUri();
		try{
			String resUri = URLEncoder.encode(resourceUri, IConstants.TEXT_ENCODING);
			String iconUrl = this.getBaseUrl() + "/oslc.png";
			String smUrl = this.getBaseUrl() + "/compact/requirement?uri=" + resUri + "&amp;type=small";
			String lgUrl = this.getBaseUrl() + "/compact/requirement?uri=" + resUri + "&amp;type=large";
			String doc = 
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?> \n" +
				"<rdf:RDF \n" +
				"   xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" \n" +
				"   xmlns:dcterms=\"http://purl.org/dc/terms/\" \n" +
				"   xmlns:oslc=\"http://open-services.net/ns/core#\"> \n" +
				" <oslc:Compact \n" +
				"   rdf:about=\"" + resourceUri + "\"> \n" +
				"   <dcterms:title>" + title + "</dcterms:title> \n" +
				"   <oslc:shortTitle>" + shortTitle + "</oslc:shortTitle> \n" +
				"   <oslc:icon rdf:resource=\"" + iconUrl + "\" /> \n" +
				"   <oslc:smallPreview> \n" +
				"      <oslc:Preview> \n" +
				"         <oslc:document rdf:resource=\"" + smUrl + "\" /> \n" +
				"         <oslc:hintWidth>500px</oslc:hintWidth> \n" +
				"         <oslc:hintHeight>120px</oslc:hintHeight> \n" +
				"      </oslc:Preview> \n" +
				"   </oslc:smallPreview> \n" +
				"   <oslc:largePreview> \n" +
				"      <oslc:Preview> \n" +
				"         <oslc:document rdf:resource=\"" + lgUrl + "\" /> \n" +
				"         <oslc:hintWidth>500px</oslc:hintWidth> \n" +
				"         <oslc:hintHeight>500px</oslc:hintHeight> \n" +
				"      </oslc:Preview> \n" +
				"   </oslc:largePreview> \n" +
				" </oslc:Compact> \n" +
				"</rdf:RDF>";
			return doc;
		} catch( UnsupportedEncodingException ex ) {
			throw new RioServiceException(ex);
		}
	}
	
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		
		String contentType = request.getContentType();
		
		InputStream content = request.getInputStream();
		
		RioStore store = this.getStore();
		if( RioStore.rdfFormatFromContentType(contentType) != null ) {
			try {
				String resUri = store.nextAvailableUri(IRmConstants.SERVICE_REQUIREMENT);
				Requirement requirement = new Requirement(resUri);
				List<RioStatement> statements = store.parse(resUri, content, contentType);
				requirement.addStatements(statements);
				String userUri = getUserUri(request.getRemoteUser());

				// if it parsed, then add it to the store.
				store.update(requirement, userUri);
				
				// now get it back, to find 
				OslcResource returnedResource = store.getOslcResource(requirement.getUri());
				Date created = returnedResource.getCreated();
				String eTag = returnedResource.getETag();
				
				response.setStatus(IConstants.SC_CREATED);
				response.setHeader(IConstants.HDR_LOCATION, requirement.getUri());
				response.setHeader(IConstants.HDR_LAST_MODIFIED, StringUtils.rfc2822(created) );
				response.setHeader(IConstants.HDR_ETAG, eTag );

			} catch (RioServerException e) {
				throw new RioServiceException(IConstants.SC_BAD, e);
			}
		}
	}

	private void processQuery(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		
		try{
			String prefix = req.getParameter("oslc.prefix"); //$NON-NLS-1$
			String select = req.getParameter("oslc.select"); //$NON-NLS-1$
			String where = req.getParameter("oslc.where"); //$NON-NLS-1$
			String orderBy = req.getParameter("oslc.orderBy"); //$NON-NLS-1$
			String searchTerms = req.getParameter("oslc.searchTerms"); //$NON-NLS-1$
			
			SimpleQueryBuilder queryBuilder = new SimpleQueryBuilder();
			queryBuilder.parseSelect(prefix);
			queryBuilder.parseSelect(select);
			queryBuilder.parseWhere("uri", where); //$NON-NLS-1$
			queryBuilder.parseSelect(orderBy);
			queryBuilder.parseSearchTerms(searchTerms);
			
			String sparql = queryBuilder.getQueryString(IRmConstants.OSLC_RM_TYPE_REQUIREMENT);
			
			RioStore store = this.getStore();
			//don't add ?null if the the query is for queryBase			
			String queryUri = (req.getQueryString() == null ) ? req.getRequestURL().toString() : req.getRequestURL().toString() + '?' + req.getQueryString();	
			
			List<Map<String, RioValue>> results = store.query(IConstants.SPARQL, sparql, 100);
			
			// TODO: create propnames
			Map<String, PName> propNames = queryBuilder.getPropertyNames();
			
			String responseResource = buildResponseResource(results, propNames, queryUri);
			resp.setContentType(IConstants.CT_RDF_XML); 
			resp.getWriter().write(responseResource); 
			resp.setStatus(IConstants.SC_OK);
			
		} catch( Exception e ) {
			throw new RioServiceException(IConstants.SC_INTERNAL_ERROR, e);
		}
	}
	
	private String buildResponseResource(List<Map<String, RioValue>> results, Map<String, PName> propNames, String reqUri) throws RioServiceException{
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setValidating(true);
		factory.setNamespaceAware(true);
		try{
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.newDocument();
			Element rdf = doc.createElementNS(IConstants.RDF_NAMESPACE, IConstants.RDF_TYPE_PTERM_RDF);
			doc.appendChild(rdf);
			
			Set<String> namespaces = namespacePrefixes.keySet();
			for (String namespace : namespaces) {
				rdf.setAttribute("xmlns:" + namespacePrefixes.get(namespace), namespace); //$NON-NLS-1$
			}
			//If there is are parameters on the request (e.g. ?oslc.where=...), create 2 subject URIs, one for the
			//results and one to describe the query.   If this query was for the queryBase itself all predicates 
			//go under the same subject.  
			//TODO: Refactor the services for all RIO artifacts to eliminate duplicated code.
			
			Element resultDescr = doc.createElementNS(IConstants.RDF_NAMESPACE, IConstants.RDF_TYPE_PTERM_DESCRIPTION);
			rdf.appendChild(resultDescr);
			
			//check for oslc query parameters
			String uriSplit [] = reqUri.split("\\?",2);
			String baseUri = uriSplit[0];
			boolean isOslcQuery = false;
			if (uriSplit.length > 1)
				isOslcQuery = hasOSLCQuery(uriSplit[1]);
			
			resultDescr.setAttributeNS(IConstants.RDF_NAMESPACE, IConstants.RDF_PTERM_ABOUT, baseUri);


			//if there are oslc query parameters, put the predicates under the query subject
			Element queryDescrElement = null;
			if (isOslcQuery )
			{
			   queryDescrElement = doc.createElementNS(IConstants.RDF_NAMESPACE, IConstants.RDF_TYPE_PTERM_DESCRIPTION);
			   queryDescrElement.setAttributeNS(IConstants.RDF_NAMESPACE, IConstants.RDF_PTERM_ABOUT, reqUri);
			   rdf.appendChild(queryDescrElement);
			}
			else {
				//no oslc query parameters, everything goes under the queryBase subject
			   queryDescrElement = resultDescr;
			}

			Element title = doc.createElementNS(IConstants.DCTERMS_NAMESPACE, IConstants.DCTERMS_PTERM_TITLE);
			queryDescrElement.appendChild(title);
			title.setTextContent(Messages.getString("ResourceQuery.Title"));
			
			Element count = doc.createElementNS(IConstants.OSLC_NAMESPACE, IConstants.OSLC_PTERM_TOTALCOUNT);
			queryDescrElement.appendChild(count);
			count.setTextContent(Integer.toString(results.size()));
			
			Element rdfType = doc.createElementNS(IConstants.RDF_NAMESPACE, IConstants.RDF_PTERM_TYPE);
			rdfType.setAttributeNS(IConstants.RDF_NAMESPACE, IConstants.RDF_PTERM_RESOURCE, IConstants.OSLC_RESPONSEINFO);
			queryDescrElement.appendChild(rdfType);
			
			Iterator<Map<String, RioValue>> iterator = results.iterator();
			while( iterator.hasNext() ) {
				
				Element rdfMem = doc.createElementNS(IConstants.RDFS_NAMESPACE, IConstants.RDFS_PTERM_MEMBER);
				resultDescr.appendChild(rdfMem);
				Map<String, RioValue> map = iterator.next();
				RioValue uri = map.get("uri"); //$NON-NLS-1$
				rdfMem.setAttributeNS(IConstants.RDF_NAMESPACE, IConstants.RDF_PTERM_RESOURCE, uri.stringValue());
			}
				
			return XmlUtils.prettyPrint(doc);
			
		} catch (ParserConfigurationException e) {
			throw new RioServiceException(e);
		} finally {
			
		}
	}
	
	private boolean hasOSLCQuery(String parms)
	{
		boolean containsOslcParm = false;
        // not perfect - could have "oslc." in some random part of the parameters, but don't
		// have access to the request at this point to use getPararmeterNames
		String [] uriParts = parms.toLowerCase().split("oslc\\.",2);
		if (uriParts.length > 1)
		{
			containsOslcParm = true;
		}
		
		return containsOslcParm;
	}
	static private Map<String,String> namespacePrefixes = new HashMap<String,String>();
	static  {
		namespacePrefixes.put(IConstants.RDF_NAMESPACE, IConstants.RDF_PREFIX);
		namespacePrefixes.put(IConstants.RDFS_NAMESPACE, IConstants.RDFS_PREFIX);
		namespacePrefixes.put(IConstants.OSLC_NAMESPACE, IConstants.OSLC_PREFIX);
		namespacePrefixes.put(IConstants.DCTERMS_NAMESPACE, IConstants.DCTERMS_PREFIX);
	}
	
}
 