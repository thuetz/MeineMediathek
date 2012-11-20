package com.halcyonwaves.apps.meinemediathek.loaders;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.acra.ACRA;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.halcyonwaves.apps.meinemediathek.SearchResultEntry;

import android.content.AsyncTaskLoader;
import android.content.Context;
import android.os.Environment;
import android.util.Log;

public class SearchLoader extends AsyncTaskLoader< List< SearchResultEntry > > {

	private List< SearchResultEntry > searchResults = null;
	private String searchFor = null;
	private static final String TAG = "SearchLoader";

	private final static String BASE_SEARCH_URL = "http://www.zdf.de/ZDFmediathek/suche?flash=off&sucheText=";
	private final static String DESKTOP_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_6_8) AppleWebKit/534.30 (KHTML, like Gecko) Chrome/12.0.742.122 Safari/534.30";
	
	private final int usedTimeoutInSeconds = 10;
	private final Pattern PreviewImagePattern = Pattern.compile( "contentblob\\/(\\d*)" );

	public SearchLoader( Context context, String searchFor ) {
		super( context );
		this.searchFor = searchFor;
	}

	@Override
	protected void onStartLoading() {
		// bugfix for this issue:
		// http://code.google.com/p/android/issues/detail?id=14944
		// http://blog.blundell-apps.com/tut-asynctask-loader-using-support-library/
		super.onStartLoading();
		if( null != this.searchResults ) {
			this.deliverResult( this.searchResults );
		} else {
			this.forceLoad();
		}
	}

	@Override
	public List< SearchResultEntry > loadInBackground() {
		// just write the search keyword into the logfile
		Log.v( SearchLoader.TAG, "Starting to load data for the following search query: " + this.searchFor );

		// be sure that the search keyword is well-formed
		String preparedSearchKeyword = "";
		try {
			preparedSearchKeyword = URLEncoder.encode( this.searchFor, "utf-8" );
		} catch( UnsupportedEncodingException e ) {
			Log.e( SearchLoader.TAG, "Failed to to a proper URL encoding of the search keywords.", e );
			ACRA.getErrorReporter().handleException( e );
		}
		Log.v( SearchLoader.TAG, "The keywords were URL encoded and are now represented as: " + preparedSearchKeyword );

		// create the list we want to return
		List< SearchResultEntry > foundTitles = new ArrayList< SearchResultEntry >();

		// try to download the response of the webpage to the search query
		try {
			// create a list with the URLs we have to visit
			List< String > linksToVisit = new ArrayList< String >();

			// loop throgh all search result pages
			String oldForwardLink = "";
			String currentForwardLink = SearchLoader.BASE_SEARCH_URL + preparedSearchKeyword;
			while( !oldForwardLink.equalsIgnoreCase( currentForwardLink ) ) {
				Log.e( SearchLoader.TAG, String.format( "Starting to parse new search results page. Currently we have %d links grabbed.", linksToVisit.size() ) );

				// query for the results and get a handle to the returned HTML code
				Document fetchedResults = Jsoup.connect( currentForwardLink ).userAgent( SearchLoader.DESKTOP_USER_AGENT ).timeout( this.usedTimeoutInSeconds * 1000 ).get();
				Elements foundLinks = fetchedResults.select( "a[href]" );
				for( Element currentLink : foundLinks ) {
					if( currentLink.attr( "href" ).contains( "/ZDFmediathek/beitrag/video" ) ) {
						linksToVisit.add( currentLink.attr( "abs:href" ) );
					}
				}

				// we have to check if there are more pages, and if there are more, we have to collect the links too
				Elements forwardLink = fetchedResults.select( "a[href].forward" );
				oldForwardLink = new String( currentForwardLink );
				currentForwardLink = new String( forwardLink.attr( "abs:href" ) );
			}

			// remove link duplicates
			HashSet< String > uniqueURLs = new HashSet< String >( linksToVisit );
			Log.e( SearchLoader.TAG, String.format( "Searching for links finished. After removing duplicates we end with %d movies pages to parse.", uniqueURLs.size() ) );

			// after we fetched the links for all of our episodes, start fetching information about the episodes
			for( String currentURL : uniqueURLs ) {

				// download the website for the selected URL
				Document currentEpisodeDoc = Jsoup.connect( currentURL ).userAgent( SearchLoader.DESKTOP_USER_AGENT ).timeout( this.usedTimeoutInSeconds * 1000 ).get();

				Elements epoisodeTitle = currentEpisodeDoc.select( "div.beitrag > p.datum" );
				Elements episodeDescription = currentEpisodeDoc.select( "div.beitrag > p.kurztext" );
				Elements episodeImage = currentEpisodeDoc.select( "div.beitrag > img" );

				// extract the unique name for the episode preview image
				String episodeImageName = "preview_000000.jpg"; // TODO
				Matcher eposiodeImagePreviewNameMatcher = this.PreviewImagePattern.matcher( episodeImage.attr( "src" ) );
				if( eposiodeImagePreviewNameMatcher.find() ) {
					episodeImageName = "preview_" + eposiodeImagePreviewNameMatcher.group( 1 ) + ".jpg";
				} else {
					episodeImageName = UUID.randomUUID().toString() + ".jpg";
				}

				// combine the extracted episode image name with the storage path
				File storagePath = this.getContext().getExternalFilesDir( Environment.DIRECTORY_PICTURES );
				File pictureFile = new File( storagePath, episodeImageName );

				// just download the preview image if it is not already cached
				if( !pictureFile.exists() ) {
					FileOutputStream pictureOutputStream = new FileOutputStream( pictureFile );

					URL imageUrl = new URL( episodeImage.first().attr( "abs:src" ) );
					URLConnection imageUrlConnection = imageUrl.openConnection();
					imageUrlConnection.setRequestProperty( "User-Agent", SearchLoader.DESKTOP_USER_AGENT );
					BufferedInputStream in = new BufferedInputStream( imageUrlConnection.getInputStream() );

					byte[] buf = new byte[ 1024 ];
					int n = 0;
					while( (n = in.read( buf )) >= 0 ) {
						pictureOutputStream.write( buf, 0, n );
					}

					pictureOutputStream.flush();
					pictureOutputStream.close();
					in.close();
				}

				// add all extracted information to our result entry representation
				foundTitles.add( new SearchResultEntry( epoisodeTitle.first().text(), episodeDescription.first().text(), pictureFile ) );

			}

		} catch( SocketTimeoutException e ) {
			Log.e( SearchLoader.TAG, "Failed to fetch the search results as a socket timedout.", e );
			ACRA.getErrorReporter().handleException( e );
		} catch( IOException e ) {
			Log.e( SearchLoader.TAG, "Failed to fetch the search results from the website.", e );
			ACRA.getErrorReporter().handleException( e );
		}

		// return the list of found items
		return foundTitles;
	}

	@Override
	public void deliverResult( List< SearchResultEntry > results ) {
		// a async query came in while the loader is stopped. We don't need the result.
		if( this.isReset() ) {
			if( results != null ) {
				this.onReleaseResources( results );
			}
		}
		List< SearchResultEntry > oldResults = results;
		this.searchResults = results;

		// if the Loader is currently started, we can immediately deliver its results.
		if( this.isStarted() ) {
			super.deliverResult( results );
		}

		// at this point we can release the resources associated with old object
		if( oldResults != null ) {
			this.onReleaseResources( oldResults );
		}
	}

	protected void onReleaseResources( List< SearchResultEntry > apps ) {
		// for a simple List<> there is nothing to do. For something
	}

}
