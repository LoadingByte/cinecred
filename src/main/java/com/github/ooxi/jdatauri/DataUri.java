/**
 * Copyright (c) 2013 ooxi
 *     https://github.com/ooxi/jdatauri
 *
 * This software is provided 'as-is', without any express or implied warranty.
 * In no event will the authors be held liable for any damages arising from the
 * use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 *  1. The origin of this software must not be misrepresented; you must not
 *     claim that you wrote the original software. If you use this software in a
 *     product, an acknowledgment in the product documentation would be
 *     appreciated but is not required.
 *
 *  2. Altered source versions must be plainly marked as such, and must not be
 *     misrepresented as being the original software.
 *
 *  3. This notice may not be removed or altered from any source distribution.
 *
 * This file has been modified by the Cinecred project to use the JDK's Base64
 * implementation in place of commons-codec.
 */
package com.github.ooxi.jdatauri;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A data URI parser
 *
 * @see https://tools.ietf.org/html/rfc2397
 * @see http://shadow2531.com/opera/testcases/datauri/data_uri_rules.html
 * @see https://en.wikipedia.org/wiki/Data_URI_scheme
 *
 * @author ooxi
 */
public class DataUri {

	private static final String CHARSET_OPTION_NAME = "charset";
	private static final String FILENAME_OPTION_NAME = "filename";
	private static final String CONTENT_DISPOSITION_OPTION_NAME = "content-disposition";

	private final String mime;
	private final Charset charset;
	private final String filename;
	private final String contentDisposition;
	private final byte[] data;



	public DataUri(String mime, Charset charset, byte[] data) {
		this(mime, charset, null, null, data);
	}

	public DataUri(String mime, Charset charset, String filename, String contentDisposition, byte[] data) {
		this.mime = mime;
		this.charset = charset;
		this.filename = filename;
		this.contentDisposition = contentDisposition;
		this.data = data;

		if (null == mime) {
			throw new NullPointerException("`mime' must not be null");
		}
		if (null == data) {
			throw new NullPointerException("`data' must not be null");
		}
	}



	public String getMime() {
		return mime;
	}

	/**
	 * @warning May be null
	 */
	public Charset getCharset() {
		return charset;
	}

	/**
	 * @warning May be null
	 */
	public String getFilename() {
		return filename;
	}

	/**
	 * @warning May be null
	 */
	public String getContentDisposition() {
		return contentDisposition;
	}

	public byte[] getData() {
		return data;
	}



	@Override
	public int hashCode() {
		int hash = 3;
		hash = 23 * hash + (this.mime != null ? this.mime.hashCode() : 0);
		hash = 23 * hash + (this.charset != null ? this.charset.hashCode() : 0);
		hash = 23 * hash + (this.filename != null ? this.filename.hashCode() : 0);
		hash = 23 * hash + (this.contentDisposition != null ? this.contentDisposition.hashCode() : 0);
		hash = 23 * hash + Arrays.hashCode(this.data);
		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final DataUri other = (DataUri) obj;
		if ((this.mime == null) ? (other.mime != null) : !this.mime.equals(other.mime)) {
			return false;
		}
		if (this.charset != other.charset && (this.charset == null || !this.charset.equals(other.charset))) {
			return false;
		}
		if ((this.filename == null) ? (other.filename != null) : !this.filename.equals(other.filename)) {
			return false;
		}
		if ((this.contentDisposition == null) ? (other.contentDisposition != null) : !this.contentDisposition.equals(other.contentDisposition)) {
			return false;
		}
		if (!Arrays.equals(this.data, other.data)) {
			return false;
		}
		return true;
	}



	/**
	 * Tries to parse a data URI described in RFC2397
	 *
	 * @param uri A string representing the data URI
	 * @param charset Charset to use when decoding percent encoded options
	 *     like filename
	 *
	 * @return Parsed data URI
	 * @throws IllegalArgumentException iff an error occured during parse
	 *     process
	 */
	public static DataUri parse(String uri, Charset charset) {

		/* If URI does not start with a case-insensitive "data:":
		 * Throw a MALFORMED_URI exception.
		 */
		if (!uri.toLowerCase().startsWith("data:")) {
			throw new IllegalArgumentException("URI must start with a case-insensitive `data:'");
		}

		/* If URI does not contain a ",":
		 * Throw a MALFORMED_URI exception.
		 */
		if (-1 == uri.indexOf(',')) {
			throw new IllegalArgumentException("URI must contain a `,'");
		}

		/* Let supportedContentEncodings be an array of strings
		 * representing the supported content encodings. (["base64"] for
		 * example)
		 */
		Collection<String> supportedContentEncodings = Arrays.asList(
			"base64"
		);

		/* Let mimeType be a string with the value "text/plain".
		 */
		String mimeType = "text/plain";

		/* Let contentEncoding be an empy string.
		 */
		String contentEncoding = "";

		/* Let contentEncodingAlreadySet be a boolean with a value of
		 * false.
		 */
		boolean contentEncodingAlreadySet = false;

		/* Let supportedValues be a map of string:string pairs where the
		 * first string in each pair represents the name of the
		 * supported value and the second string in each pair represents
		 * an empty string or default string value. (Example: {"charset"
		 * : "", "filename" : "", "content-disposition" : ""})
		 */
		final Map<String, String> supportedValues = new HashMap<String, String>() {{
			put(CHARSET_OPTION_NAME, "");
			put(FILENAME_OPTION_NAME, "");
			put(CONTENT_DISPOSITION_OPTION_NAME, "");
		}};

		/* Let supportedValueSetBits be a map of string:bool pairs
		 * representing each of the names in supportedValues with each
		 * name set to false.
		 */
		final Map<String, Boolean> supportedValueSetBits = new HashMap<String, Boolean>() {{
			for (String key : supportedValues.keySet()) {
				put(key, false);
			}
		}};

		/* Let comma be the position of the first "," found in URI.
		 */
		int comma = uri.indexOf(',');

		/* Let temp be the substring of URI from, and including,
		 * position 5 to, and excluding, the comma position. (between
		 * "data:" and first ",")
		 */
		String temp = uri.substring("data:".length(), comma);

		/* Let headers be an array of strings returned by splitting temp
		 * by ";".
		 */
		String[] headers = temp.split(";");

		/* For each string s in headers:
		 */
		for (int header = 0; header < headers.length; ++header) {
			String s = headers[header];

			/* Let s equal the lowercase version of s
			 */
			s = s.toLowerCase();

			/* Let eq be the position result of searching for "=" in
			 * s.
			 */
			int eq = s.indexOf('=');

			/* Let name and value be empty strings.
			 */
			String name;
			String value = "";

			/* If eq is not a valid position in s:
			 */
			if (-1 == eq) {

				/* Let name equal the result of percent-decoding
				 * s.
				 */
				name = percentDecode(s, charset);

				/* Let name equal the result of trimming leading
				 * and trailing white-space from name.
				 */
				name = name.trim();

			/* Else:
			 */
			} else {

				/* Let name equal the substring of s from
				 * position 0 to, but not including, position
				 * eq.
				 */
				name = s.substring(0, eq);

				/* Let name equal the result of percent-decoding
				 * name.
				 */
				name = percentDecode(name, charset);

				/* Let name equal the result of trimmnig leading
				 * and trailing white-space from name.
				 */
				name = name.trim();

				/* Let value equal the substring of s from
				 * position eq + 1 to the end of s.
				 */
				value = s.substring(eq + 1);

				/* Let value equal the result of precent-
				 * decoding value.
				 */
				value = percentDecode(value, charset);

				/* Let value equal the result of trimming
				 * leading and trailing white-space from value.
				 */
				value = value.trim();
			}

			/* If s is the first element in headers and eq is not a
			 * valid position in s and the length of name is greater
			 * than 0:
			 */
			if ((0 == header) && (-1 == eq) && !name.isEmpty()) {

				/* Let mimeType equal name.
				 */
				mimeType = name;

			/* Else:
			 */
			} else {

				/* If eq is not a valid position in s:
				 */
				if (-1 == eq) {

					/* If name is found case-insensitively
					 * in supportedContentEncodings:
					 */
					final String nameCaseInsensitive = name.toLowerCase();

					if (supportedContentEncodings.contains(nameCaseInsensitive)) {

						/* If contentEncodingAlreadySet
						 * is false:
						 */
						if (!contentEncodingAlreadySet) {

							/* Let contentEncoding
							 * equal name.
							 */
							contentEncoding = name;

							/* Let contentEncodingAlreadySet
							 * equal true.
							 */
							contentEncodingAlreadySet = true;
						}
					}

				/* Else:
				 */
				} else {

					/* If the length of value is greater
					 * than 0 and name is found case-
					 * insensitively in supportedValues:
					 */
					final String nameCaseInsensitive = name.toLowerCase();

					if (!value.isEmpty() && supportedValues.containsKey(nameCaseInsensitive)) {

						/* If the corresponding value
						 * for name found (case-
						 * insensitivley) in
						 * supportedValueSetBits is
						 * false:
						 */
						boolean valueSet = supportedValueSetBits.get(nameCaseInsensitive);

						if (!valueSet) {

							/* Let the corresponding
							 * value for name found
							 * (case-insensitively)
							 * in supportedValues
							 * equal value.
							 */
							supportedValues.put(nameCaseInsensitive, value);

							/* Let the corresponding
							 * value for name found
							 * (case-insensitively)
							 * in supportedValueSetBits
							 * equal true.
							 */
							supportedValueSetBits.put(nameCaseInsensitive, true);
						}
					}
				}
			}

		}

		/* Let data be the substring of URI from position comma + 1 to
		 * the end of URI.
		 */
		String data = uri.substring(comma + 1);

		/* Let data be the result of percent-decoding data.
		 */
		data = percentDecode(data, charset);

		/* Let dataURIObject be an object consisting of the mimeType,
		 * contentEncoding, data and supportedValues objects.
		 */
		final String finalMimeType = mimeType;
		final Charset finalCharset = supportedValues.get(CHARSET_OPTION_NAME).isEmpty()
			? null : Charset.forName(supportedValues.get(CHARSET_OPTION_NAME));
		final String finalFilename = supportedValues.get(FILENAME_OPTION_NAME).isEmpty()
			? null : supportedValues.get(FILENAME_OPTION_NAME);
		final String finalContentDisposition = supportedValues.get(CONTENT_DISPOSITION_OPTION_NAME).isEmpty()
			? null : supportedValues.get(CONTENT_DISPOSITION_OPTION_NAME);
		final byte[] finalData = "base64".equalsIgnoreCase(contentEncoding)
			? Base64.getDecoder().decode(data) : data.getBytes(charset);

		DataUri dataURIObject = new DataUri(
			finalMimeType,
			finalCharset,
			finalFilename,
			finalContentDisposition,
			finalData
		);

		/* return dataURIObject.
		 */
		return dataURIObject;
	}

	@Override
	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append("data:").append(this.getMime()).append(";");

		if (this.charset != null) {
			s.append(CHARSET_OPTION_NAME + "=").append(this.charset.name()).append(";");
		}

		if (this.contentDisposition != null) {
			s.append(CONTENT_DISPOSITION_OPTION_NAME + "=").append(this.contentDisposition).append(";");
		}

		if (this.filename != null) {
			s.append(FILENAME_OPTION_NAME + "=").append(this.filename).append(";");
		}

		s.append("base64,").append(Base64.getEncoder().encodeToString(this.getData()));

		return s.toString();
	}

	private static final Pattern PLUS = Pattern.compile("+", Pattern.LITERAL);

	private static String percentDecode(String s, Charset cs) {
		try {
			// We only need to decode %hh escape sequences, while
			// URLDecoder.decode in addition to that also replaces '+' with space.
			// As a workaround we first replace all pluses with %2B sequence,
			// so that they are preserved after decoding.
			s = PLUS.matcher(s).replaceAll("%2B");

			return URLDecoder.decode(s, cs.name());
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException("Charset `"+ cs.name() +"' not supported", e);
		}
	}
}
