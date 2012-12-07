/*
 * Copyright (C) 2006-2010 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */

package org.alfresco.jlan.smb;

/**
 * OpLock Types Class
 * 
 * <p>Contains oplock constants
 * 
 * @author gkspencer
 */
public class OpLock {

	// Oplock types
	
	public static final int TypeNone 		= 0;
	public static final int TypeExclusive	= 1;
	public static final int TypeBatch		= 2;
	
	/**
	 * Return the oplock type as a string
	 * 
	 * @param typ int
	 * @return String
	 */
	public static String getTypeAsString(int typ) {
		String typStr = "";
		
		switch ( typ) {
			case TypeNone:
				typStr = "None";
				break;
			case TypeBatch:
				typStr = "Batch";
				break;
			case TypeExclusive:
				typStr = "Exclusive";
				break;
		}
		
		return typStr;
	}
}
