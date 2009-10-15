/**
 * Copyright (C) 2005-2008 Alfresco Software Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.

 * As a special exception to the terms and conditions of version 2.0 of 
 * the GPL, you may redistribute this Program in connection with Free/Libre 
 * and Open Source Software ("FLOSS") applications as described in Alfresco's 
 * FLOSS exception.  You should have recieved a copy of the text describing 
 * the FLOSS exception, and it is also available here: 
 * http://www.alfresco.com/legal/licensing
 */
 
/**
 * DocumentList Toolbar component.
 * 
 * @namespace Alfresco
 * @class Alfresco.DocListToolbar
 */
(function()
{
   /**
    * YUI Library aliases
    */
   var Dom = YAHOO.util.Dom,
      Event = YAHOO.util.Event,
      Element = YAHOO.util.Element;

   /**
    * Alfresco Slingshot aliases
    */
   var $html = Alfresco.util.encodeHTML,
      $combine = Alfresco.util.combinePaths;

   /**
    * Preferences
    */
   var PREFERENCES_ROOT = "org.alfresco.share.documentList",
      PREF_HIDE_NAVBAR = PREFERENCES_ROOT + ".hideNavBar";
   
   /**
    * DocListToolbar constructor.
    * 
    * @param {String} htmlId The HTML id of the parent element
    * @return {Alfresco.DocListToolbar} The new DocListToolbar instance
    * @constructor
    */
   Alfresco.DocListToolbar = function(htmlId)
   {
      Alfresco.DocListToolbar.superclass.constructor.call(this, "Alfresco.DocListToolbar", htmlId, ["button", "menu", "container"]);
      
      // Initialise prototype properties
      this.selectedFiles = [];
      this.currentFilter = {};

      // Decoupled event listeners
      YAHOO.Bubbling.on("filterChanged", this.onFilterChanged, this);
      YAHOO.Bubbling.on("deactivateAllControls", this.onDeactivateAllControls, this);
      YAHOO.Bubbling.on("selectedFilesChanged", this.onSelectedFilesChanged, this);
      YAHOO.Bubbling.on("userAccess", this.onUserAccess, this);
      YAHOO.Bubbling.on("doclistMetadata", this.onDoclistMetadata, this);

      return this;
   };

   /**
    * Extend from Alfresco.component.Base
    */
   YAHOO.extend(Alfresco.DocListToolbar, Alfresco.component.Base);
   
   /**
    * Augment prototype with Actions module
    */
   YAHOO.lang.augmentProto(Alfresco.DocListToolbar, Alfresco.doclib.Actions);
   
   /**
    * Augment prototype with main class implementation, ensuring overwrite is enabled
    */
   YAHOO.lang.augmentObject(Alfresco.DocListToolbar.prototype,
   {
      /**
       * Object container for initialization options
       *
       * @property options
       * @type object
       */
      options:
      {
         /**
          * Current siteId.
          * 
          * @property siteId
          * @type string
          */
         siteId: "",

         /**
          * ContainerId representing root container
          *
          * @property containerId
          * @type string
          * @default "documentLibrary"
          */
         containerId: "documentLibrary",

         /**
          * Number of multi-file uploads before grouping the Activity Post
          *
          * @property groupActivitiesAt
          * @type int
          * @default 5
          */
         groupActivitiesAt: 5,
         
         /**
          * Flag indicating whether navigation bar is visible or not.
          * 
          * @property hideNavBar
          * @type boolean
          */
         hideNavBar: false
      },
      
      /**
       * Current path being browsed.
       * 
       * @property currentPath
       * @type string
       */
      currentPath: "",

      /**
       * Current filter to choose toolbar view and populate description.
       * 
       * @property currentFilter
       * @type string
       */
      currentFilter: null,

      /**
       * FileUpload module instance.
       * 
       * @property fileUpload
       * @type Alfresco.FileUpload
       */
      fileUpload: null,

      /**
       * Array of selected states for visible files.
       * 
       * @property selectedFiles
       * @type array
       */
      selectedFiles: null,

      /**
       * Dom ID of last crumb.
       * 
       * @property lastCrumbId
       * @type string
       */
      lastCrumbId: null,

      /**
       * Fired by YUI when parent element is available for scripting.
       * Component initialisation, including instantiation of YUI widgets and event listener binding.
       *
       * @method onReady
       */
      onReady: function DLTB_onReady()
      {
         // New Folder button: user needs "create" access
         this.widgets.newFolder = Alfresco.util.createYUIButton(this, "newFolder-button", this.onNewFolder,
         {
            disabled: true,
            value: "create"
         });
         
         // File Upload button: user needs  "create" access
         this.widgets.fileUpload = Alfresco.util.createYUIButton(this, "fileUpload-button", this.onFileUpload,
         {
            disabled: true,
            value: "create"
         });

         // Selected Items menu button
         this.widgets.selectedItems = Alfresco.util.createYUIButton(this, "selectedItems-button", this.onSelectedItems,
         {
            type: "menu", 
            menu: "selectedItems-menu",
            disabled: true
         });
         // Clear the lazyLoad flag and fire init event to get menu rendered into the DOM
         var menu = this.widgets.selectedItems.getMenu();
         menu.lazyLoad = false;
         menu.initEvent.fire();
         menu.render();

         // Customize button
         this.widgets.customize = Alfresco.util.createYUIButton(this, "customize-button", this.onCustomize);

         // Hide/Show NavBar button
         this.widgets.hideNavBar = Alfresco.util.createYUIButton(this, "hideNavBar-button", this.onHideNavBar);
         this.widgets.hideNavBar.set("label", this.msg(this.options.hideNavBar ? "button.navbar.show" : "button.navbar.hide"));
         Dom.setStyle(this.id + "-navBar", "display", this.options.hideNavBar ? "none" : "block");
         
         // RSS Feed link button
         this.widgets.rssFeed = Alfresco.util.createYUIButton(this, "rssFeed-button", null, 
         {
            type: "link"
         });

         // Folder Up Navigation button
         this.widgets.folderUp =  Alfresco.util.createYUIButton(this, "folderUp-button", this.onFolderUp,
         {
            disabled: true
         });

         // DocLib Actions module
         this.modules.actions = new Alfresco.module.DoclibActions();
         
         // Reference to Document List component
         this.modules.docList = Alfresco.util.ComponentManager.findFirst("Alfresco.DocumentList");

         // Preferences service
         this.services.preferences = new Alfresco.service.Preferences();

         // Finally show the component body here to prevent UI artifacts on YUI button decoration
         Dom.setStyle(this.id + "-body", "visibility", "visible");
      },
      

      /**
       * YUI WIDGET EVENT HANDLERS
       * Handlers for standard events fired from YUI widgets, e.g. "click"
       */

      /**
       * New Folder button click handler
       *
       * @method onNewFolder
       * @param e {object} DomEvent
       * @param p_obj {object} Object passed back from addListener method
       */
      onNewFolder: function DLTB_onNewFolder(e, p_obj)
      {
         var actionUrl = Alfresco.constants.PROXY_URI + $combine("slingshot/doclib/action/folder/site", this.options.siteId, this.options.containerId, this.currentPath);

         var doSetupFormsValidation = function DLTB_oNF_doSetupFormsValidation(p_form)
         {
            // Validation
            p_form.addValidation(this.id + "-createFolder-name", Alfresco.forms.validation.mandatory, null, "blur");
            p_form.addValidation(this.id + "-createFolder-name", Alfresco.forms.validation.nodeName, null, "keyup");
            p_form.addValidation(this.id + "-createFolder-name", Alfresco.forms.validation.length,
            {
               max: 256,
               crop: true
            }, "keyup");
            p_form.addValidation(this.id + "-createFolder-title", Alfresco.forms.validation.length,
            {
               max: 256,
               crop: true
            }, "keyup");
            p_form.addValidation(this.id + "-createFolder-description", Alfresco.forms.validation.length,
            {
               max: 512,
               crop: true
            }, "keyup");
            p_form.setShowSubmitStateDynamically(true, false);
         };

         if (!this.modules.createFolder)
         {
            this.modules.createFolder = new Alfresco.module.SimpleDialog(this.id + "-createFolder").setOptions(
            {
               width: "30em",
               templateUrl: Alfresco.constants.URL_SERVICECONTEXT + "modules/documentlibrary/create-folder",
               actionUrl: actionUrl,
               doSetupFormsValidation:
               {
                  fn: doSetupFormsValidation,
                  scope: this
               },
               firstFocus: this.id + "-createFolder-name",
               onSuccess:
               {
                  fn: function DLTB_onNewFolder_callback(response)
                  {
                     var folder = response.json.results[0];
                     YAHOO.Bubbling.fire("folderCreated",
                     {
                        name: folder.name,
                        parentPath: folder.parentPath,
                        nodeRef: folder.nodeRef
                     });
                     Alfresco.util.PopupManager.displayMessage(
                     {
                        text: this.msg("message.new-folder.success", folder.name)
                     });
                  },
                  scope: this
               }
            });
         }
         else
         {
            this.modules.createFolder.setOptions(
            {
               actionUrl: actionUrl,
               clearForm: true
            });
         }
         this.modules.createFolder.show();
      },

      /**
       * File Upload button click handler
       *
       * @method onFileUpload
       * @param e {object} DomEvent
       * @param p_obj {object} Object passed back from addListener method
       */
      onFileUpload: function DLTB_onFileUpload(e, p_obj)
      {
         if (this.fileUpload === null)
         {
            this.fileUpload = Alfresco.getFileUploadInstance(); 
         }
         
         // Show uploader for multiple files
         var multiUploadConfig =
         {
            siteId: this.options.siteId,
            containerId: this.options.containerId,
            uploadDirectory: this.currentPath,
            filter: [],
            mode: this.fileUpload.MODE_MULTI_UPLOAD,
            thumbnails: "doclib",
            onFileUploadComplete:
            {
               fn: this.onFileUploadComplete,
               scope: this
            }
         };
         this.fileUpload.show(multiUploadConfig);
      },
      
      /**
       * File Upload complete event handler
       *
       * @method onFileUploadComplete
       * @param complete {object} Object literal containing details of successful and failed uploads
       */
      onFileUploadComplete: function DLTB_onFileUploadComplete(complete)
      {
         var success = complete.successful.length, activityData, file;
         if (success > 0)
         {
            if (success < this.options.groupActivitiesAt)
            {
               // Below cutoff for grouping Activities into one
               for (var i = 0; i < success; i++)
               {
                  file = complete.successful[i];
                  activityData =
                  {
                     fileName: file.fileName,
                     nodeRef: file.nodeRef
                  };
                  this.modules.actions.postActivity(this.options.siteId, "file-added", "document-details", activityData);
               }
            }
            else
            {
               // grouped into one message
               activityData =
               {
                  fileCount: success,
                  path: this.currentPath
               };
               this.modules.actions.postActivity(this.options.siteId, "files-added", "documentlibrary", activityData);
            }
         }
      },

       /**
        * Selected Items button click handler
        *
        * @method onSelectedItems
        * @param sType {string} Event type, e.g. "click"
        * @param aArgs {array} Arguments array, [0] = DomEvent, [1] = EventTarget
        * @param p_obj {object} Object passed back from subscribe method
        */
      onSelectedItems: function DLTB_onSelectedItems(sType, aArgs, p_obj)
      {
         var domEvent = aArgs[0],
            eventTarget = aArgs[1];

         // Check mandatory docList module is present
         if (this.modules.docList)
         {
            // Get the function related to the clicked item
            var fn = Alfresco.util.findEventClass(eventTarget);
            if (fn && (typeof this[fn] == "function"))
            {
               this[fn].call(this, this.modules.docList.getSelectedFiles());
            }
         }

         Event.preventDefault(domEvent);
      },
      
      /**
       * Delete Multiple Assets.
       *
       * @method onActionDelete
       * @param assets {object} Object literal representing one or more file(s) or folder(s) to be actioned
       */
      onActionDelete: function DLTB_onActionDelete(assets)
      {
         var me = this,
            fileNames = [];
         
         for (var i = 0, j = assets.length; i < j; i++)
         {
            fileNames.push("<span class=\"" + assets[i].type + "\">" + $html(assets[i].displayName) + "</span>");
         }
         
         var confirmTitle = this.msg("title.multiple-delete.confirm"),
            confirmMsg = this.msg("message.multiple-delete.confirm", assets.length);
         confirmMsg += "<div class=\"toolbar-file-list\">" + fileNames.join("") + "</div>";

         Alfresco.util.PopupManager.displayPrompt(
         {
            title: confirmTitle,
            text: confirmMsg,
            noEscape: true,
            modal: true,
            buttons: [
            {
               text: this.msg("button.delete"),
               handler: function DLTB_onActionDelete_delete()
               {
                  this.destroy();
                  me._onActionDeleteConfirm.call(me, assets);
               }
            },
            {
               text: this.msg("button.cancel"),
               handler: function DLTB_onActionDelete_cancel()
               {
                  this.destroy();
               },
               isDefault: true
            }]
         });
      },

      /**
       * Delete Multiple Assets confirmed.
       *
       * @method _onActionDeleteConfirm
       * @param assets {array} Array containing assets to be deleted
       * @private
       */
      _onActionDeleteConfirm: function DLTB__onActionDeleteConfirm(assets)
      {
         var multipleAssets = [], i, ii;
         for (i = 0, ii = assets.length; i < ii; i++)
         {
            multipleAssets.push(assets[i].nodeRef);
         }
         
         // Success callback function
         var fnSuccess = function DLTB__oADC_success(data, assets)
         {
            var result;
            var successCount = 0;

            // Did the operation succeed?
            if (!data.json.overallSuccess)
            {
               Alfresco.util.PopupManager.displayMessage(
               {
                  text: this.msg("message.multiple-delete.failure")
               });
               return;
            }

            YAHOO.Bubbling.fire("filesDeleted");

            for (i = 0, ii = data.json.totalResults; i < ii; i++)
            {
               result = data.json.results[i];
               
               if (result.success)
               {
                  successCount++;
                  
                  YAHOO.Bubbling.fire(result.type == "folder" ? "folderDeleted" : "fileDeleted",
                  {
                     multiple: true,
                     nodeRef: result.nodeRef
                  });
               }
            }

            // Activities
            var activityData;
            if (successCount > 0)
            {
               if (successCount < this.options.groupActivitiesAt)
               {
                  // Below cutoff for grouping Activities into one
                  for (i = 0; i < successCount; i++)
                  {
                     activityData =
                     {
                        fileName: data.json.results[i].id,
                        path: this.currentPath
                     };
                     this.modules.actions.postActivity(this.options.siteId, "file-deleted", "documentlibrary", activityData);
                  }
               }
               else
               {
                  // grouped into one message
                  activityData =
                  {
                     fileCount: successCount,
                     path: this.currentPath
                  };
                  this.modules.actions.postActivity(this.options.siteId, "files-deleted", "documentlibrary", activityData);
               }
            }

            Alfresco.util.PopupManager.displayMessage(
            {
               text: this.msg("message.multiple-delete.success", successCount)
            });
         };
         
         // Construct the data object for the genericAction call
         this.modules.actions.genericAction(
         {
            success:
            {
               callback:
               {
                  fn: fnSuccess,
                  scope: this,
                  obj: assets
               }
            },
            failure:
            {
               message: this.msg("message.multiple-delete.failure")
            },
            webscript:
            {
               method: Alfresco.util.Ajax.DELETE,
               name: "files"
            },
            wait:
            {
               message: this.msg("message.multiple-delete.please-wait")
            },
            config:
            {
               requestContentType: Alfresco.util.Ajax.JSON,
               dataObj:
               {
                  nodeRefs: multipleAssets
               }
            }
         });
      },

      /**
       * Deselect currectly selected assets.
       *
       * @method onActionDeselectAll
       */
      onActionDeselectAll: function DLTB_onActionDeselectAll()
      {
         this.modules.docList.selectFiles("selectNone");
      },

      /**
       * Customize button click handler
       *
       * @method onCustomize
       * @param e {object} DomEvent
       * @param p_obj {object} Object passed back from addListener method
       */
      onCustomize: function DLTB_onCustomize(e, p_obj)
      {
         
      },

      /**
       * Show/Hide navigation bar button click handler
       *
       * @method onHideNavBar
       * @param e {object} DomEvent
       * @param p_obj {object} Object passed back from addListener method
       */
      onHideNavBar: function DLTB_onHideNavBar(e, p_obj)
      {
         this.options.hideNavBar = !this.options.hideNavBar;
         p_obj.set("label", this.msg(this.options.hideNavBar ? "button.navbar.show" : "button.navbar.hide"));

         this.services.preferences.set(PREF_HIDE_NAVBAR, this.options.hideNavBar);

         Dom.setStyle(this.id + "-navBar", "display", this.options.hideNavBar ? "none" : "block");
         Event.preventDefault(e);
      },

      /**
       * Folder Up Navigate button click handler
       *
       * @method onFolderUp
       * @param e {object} DomEvent
       * @param p_obj {object} Object passed back from addListener method
       */
      onFolderUp: function DLTB_onFolderUp(e, p_obj)
      {
         var newPath = this.currentPath.substring(0, this.currentPath.lastIndexOf("/")),
            filter = this.currentFilter;
         
         filter.filterData = newPath;

         YAHOO.Bubbling.fire("filterChanged", filter);
         Event.preventDefault(e);
      },

      /**
       * Path Changed handler
       *
       * @method pathChanged
       * @param path {string} New path
       */
      pathChanged: function DLTB_pathChanged(path)
      {
         this.currentPath = path;
         this._generateBreadcrumb();
         this._generateRSSFeedUrl();
         
         // Enable/disable the Folder Up button
         var paths = this.currentPath.split("/");
         // Check for root path special case
         if (this.currentPath === "/")
         {
            paths = ["/"];
         }
         this.widgets.folderUp.set("disabled", paths.length < 2);
      },
      

      /**
       * BUBBLING LIBRARY EVENT HANDLERS FOR PAGE EVENTS
       * Disconnected event handlers for inter-component event notification
       */

      /**
       * Filter Changed event handler
       *
       * @method onFilterChanged
       * @param layer {object} Event fired
       * @param args {array} Event parameters (depends on event type)
       */
      onFilterChanged: function DLTB_onFilterChanged(layer, args)
      {
         var obj = args[1];
         if (obj && (typeof obj.filterId !== "undefined"))
         {
            obj.filterOwner = obj.filterOwner || Alfresco.util.FilterManager.getOwner(obj.filterId);

            if (this.currentFilter.filterOwner != obj.filterOwner || this.currentFilter.filterId != obj.filterId)
            {
               var filterOwner = obj.filterOwner.split(".")[1],
                  ownerIdClass = filterOwner + "_" + obj.filterId;
               
               // Obtain array of DIVs we might want to hide
               var divs = YAHOO.util.Selector.query('div.hideable', Dom.get(this.id)), div;
               for (var i = 0, j = divs.length; i < j; i++)
               {
                  div = divs[i];
                  if (Dom.hasClass(div, filterOwner) || Dom.hasClass(div, ownerIdClass))
                  {
                     Dom.removeClass(div, "toolbar-hidden");
                  }
                  else
                  {
                     Dom.addClass(div, "toolbar-hidden");
                  }
               }
            }
            
            Alfresco.logger.debug("DLTB_onFilterChanged", "Old Filter", this.currentFilter);
            this.currentFilter = Alfresco.util.cleanBubblingObject(obj);
            Alfresco.logger.debug("DLTB_onFilterChanged", "New Filter", this.currentFilter);
            
            if (this.currentFilter.filterId == "path")
            {
               this.pathChanged(this.currentFilter.filterData);
            }
            else
            {
               this._generateDescription();
               this._generateRSSFeedUrl();
            }
         }
      },

      /**
       * Deactivate All Controls event handler
       *
       * @method onDeactivateAllControls
       * @param layer {object} Event fired
       * @param args {array} Event parameters (depends on event type)
       */
      onDeactivateAllControls: function DLTB_onDeactivateAllControls(layer, args)
      {
         var index, fnDisable = Alfresco.util.disableYUIButton;
         for (index in this.widgets)
         {
            if (this.widgets.hasOwnProperty(index))
            {
               fnDisable(this.widgets[index]);
            }
         }
      },

      /**
       * User Access event handler
       *
       * @method onUserAccess
       * @param layer {object} Event fired
       * @param args {array} Event parameters (depends on event type)
       */
      onUserAccess: function DLTB_onUserAccess(layer, args)
      {
         var obj = args[1];
         if (obj && obj.userAccess)
         {
            var widget, widgetPermissions, index, orPermissions, orMatch;
            for (index in this.widgets)
            {
               if (this.widgets.hasOwnProperty(index))
               {
                  widget = this.widgets[index];
                  // Skip if this action specifies "no-access-check"
                  if (widget.get("srcelement").className != "no-access-check")
                  {
                     // Default to disabled: must be enabled via permission
                     widget.set("disabled", false);
                     if (typeof widget.get("value") == "string")
                     {
                        // Comma-separation indicates "AND"
                        widgetPermissions = widget.get("value").split(",");
                        for (var i = 0, ii = widgetPermissions.length; i < ii; i++)
                        {
                           // Pipe-separation is a special case and indicates an "OR" match. The matched permission is stored in "activePermission" on the widget.
                           if (widgetPermissions[i].indexOf("|") !== -1)
                           {
                              orMatch = false;
                              orPermissions = widgetPermissions[i].split("|");
                              for (var j = 0, jj = orPermissions.length; j < jj; j++)
                              {
                                 if (obj.userAccess[orPermissions[j]])
                                 {
                                    orMatch = true;
                                    widget.set("activePermission", orPermissions[j], true);
                                    break;
                                 }
                              }
                              if (!orMatch)
                              {
                                 widget.set("disabled", true);
                                 break;
                              }
                           }
                           else if (!obj.userAccess[widgetPermissions[i]])
                           {
                              widget.set("disabled", true);
                              break;
                           }
                        }
                     }
                  }
               }
            }
         }
      },

      /**
       * Selected Files Changed event handler.
       * Determines whether to enable or disable the multi-file action drop-down
       *
       * @method onSelectedFilesChanged
       * @param layer {object} Event fired
       * @param args {array} Event parameters (depends on event type)
       */
      onSelectedFilesChanged: function DLTB_onSelectedFilesChanged(layer, args)
      {
         if (this.modules.docList)
         {
            var files = this.modules.docList.getSelectedFiles(), fileTypes = [], file,
               userAccess = {}, fileAccess, index,
               menuItems = this.widgets.selectedItems.getMenu().getItems(), menuItem,
               actionPermissions, typeGroups, typesSupported, disabled,
               i, ii, j, jj;
            
            // Check each file for user permissions
            for (i = 0, ii = files.length; i < ii; i++)
            {
               file = files[i];
               
               // Required user access level - logical AND of each file's permissions
               fileAccess = file.permissions.userAccess;
               for (index in fileAccess)
               {
                  if (fileAccess.hasOwnProperty(index))
                  {
                     userAccess[index] = (userAccess[index] === undefined ? fileAccess[index] : userAccess[index] && fileAccess[index]);
                  }
               }
               
               // Make a note of all selected file types Using a hybrid array/object so we can use both array.length and "x in object"
               if (!(file.type in fileTypes))
               {
                  fileTypes[file.type] = true;
                  fileTypes.push(file.type);
               }
            }

            // Now go through the menu items, setting the disabled flag appropriately
            for (index in menuItems)
            {
               if (menuItems.hasOwnProperty(index))
               {
                  // Defaulting to enabled
                  menuItem = menuItems[index];
                  disabled = false;

                  if (menuItem.element.firstChild)
                  {
                     // Check permissions required - stored in "rel" attribute in the DOM
                     if (menuItem.element.firstChild.rel && menuItem.element.firstChild.rel !== "")
                     {
                        // Comma-separated indicates and "AND" match
                        actionPermissions = menuItem.element.firstChild.rel.split(",");
                        for (i = 0, ii = actionPermissions.length; i < ii; i++)
                        {
                           // Disable if the user doesn't have ALL the permissions
                           if (!userAccess[actionPermissions[i]])
                           {
                              disabled = true;
                              break;
                           }
                        }
                     }

                     if (!disabled)
                     {
                        // Check filetypes supported
                        if (menuItem.element.firstChild.type && menuItem.element.firstChild.type !== "")
                        {
                           // Pipe-separation indicates grouping of allowed file types
                           typeGroups = menuItem.element.firstChild.type.split("|");
                           
                           for (i = 0; i < typeGroups.length; i++) // Do not optimize - bounds updated within loop
                           {
                              typesSupported = Alfresco.util.arrayToObject(typeGroups[i].split(","));

                              for (j = 0, jj = fileTypes.length; j < jj; j++)
                              {
                                 if (!(fileTypes[j] in typesSupported))
                                 {
                                    typeGroups.splice(i, 1);
                                    --i;
                                    break;
                                 }
                              }
                           }
                           disabled = (typeGroups.length === 0);
                        }
                     }
                     menuItem.cfg.setProperty("disabled", disabled);
                  }
               }
            }
            this.widgets.selectedItems.set("disabled", (files.length === 0));
         }
      },

      /**
       * Document List Metadata event handler
       * NOTE: This is a temporary fix to enable access to the View Details action from the breadcrumb.
       *       A more complete solution is to present the full list of parent folder actions.
       *
       * @method onDoclistMetadata
       * @param layer {object} Event fired
       * @param args {array} Event parameters (depends on event type)
       */
      onDoclistMetadata: function DLTB_onDoclistMetadata(layer, args)
      {
         var obj = args[1];
         if (obj && obj.metadata)
         {
            var crumb = Dom.get(this.lastCrumbId);
            if (crumb)
            {
               var p = obj.metadata.parent,
                  folderDetailsUrl = Alfresco.constants.URL_PAGECONTEXT + "site/" + this.options.siteId + "/folder-details?nodeRef=" + p.nodeRef;
               crumb.innerHTML = '<a href="' + folderDetailsUrl + '">' + crumb.innerHTML + '</a>';
            }
         }
      },
      
   
      /**
       * PRIVATE FUNCTIONS
       */

      /**
       * Generates the HTML mark-up for the breadcrumb from the currentPath
       *
       * @method _generateBreadcrumb
       * @private
       */
      _generateBreadcrumb: function DLTB__generateBreadcrumb()
      {
         var divBC = Dom.get(this.id + "-breadcrumb");
         if (divBC === null)
         {
            return;
         }
         divBC.innerHTML = "";
         
         var paths = this.currentPath.split("/");
         // Check for root path special case
         if (this.currentPath === "/")
         {
            paths = ["/"];
         }
         // Clone the array and re-use the root node name from the DocListTree
         var me = this,
            displayPaths = paths.concat();
         
         displayPaths[0] = Alfresco.util.message("node.root", "Alfresco.DocListTree");

         var fnCrumbIconClick = function DLTB__fnCrumbIconClick(e, path)
         {
            Dom.addClass(e.target.parentNode, "highlighted");
            Event.stopEvent(e);
         };

         var fnBreadcrumbClick = function DLTB__fnBreadcrumbClick(e, path)
         {
            var filter = me.currentFilter;
            filter.filterData = path;
            
            YAHOO.Bubbling.fire("filterChanged", filter);
            Event.stopEvent(e);
         };
         
         var eBreadcrumb = new Element(divBC),
            newPath,
            eCrumb,
            eIcon,
            eFolder;
         
         for (var i = 0, j = paths.length; i < j; ++i)
         {
            newPath = paths.slice(0, i+1).join("/");
            eCrumb = new Element(document.createElement("div"));
            eCrumb.addClass("crumb");
            
            // First crumb doesn't get an icon
            if (i > 0)
            {
               eIcon = new Element(document.createElement("a"),
               {
                  href: "#",
                  innerHTML: "&nbsp;"
               });
               eIcon.on("click", fnBreadcrumbClick, newPath);
               eIcon.addClass("icon");
               eCrumb.appendChild(eIcon);
            }

            // Last crumb shouldn't be rendered as a link until doclistMetadata is available
            if (j - i < 2)
            {
               eFolder = new Element(document.createElement("span"),
               {
                  innerHTML: $html(displayPaths[i])
               });
               if (j > 1)
               {
                  this.lastCrumbId = Alfresco.util.generateDomId(eFolder.get("element"));
               }
               else
               {
                  this.lastCrumbId = null;
               }
               eFolder.addClass("folder");
               eCrumb.appendChild(eFolder);
               eBreadcrumb.appendChild(eCrumb);
            }
            else
            {
               eFolder = new Element(document.createElement("a"),
               {
                  href: "",
                  innerHTML: $html(displayPaths[i])
               });
               eFolder.addClass("folder");
               eFolder.on("click", fnBreadcrumbClick, newPath);
               eCrumb.appendChild(eFolder);
               eBreadcrumb.appendChild(eCrumb);
               eBreadcrumb.appendChild(new Element(document.createElement("div"),
               {
                  innerHTML: "&gt;",
                  className: "separator"
               }));
            }
         }
      },

      /**
       * Generates the HTML mark-up for the description from the currentFilter
       *
       * @method _generateDescription
       * @private
       */
      _generateDescription: function DLTB__generateDescription()
      {
         var divDesc, eDivDesc, eDescMsg, eDescMore, filterDisplay;
         
         divDesc = Dom.get(this.id + "-description");
         if (divDesc === null)
         {
            return;
         }
         
         while (divDesc.hasChildNodes())
         {
            divDesc.removeChild(divDesc.lastChild);
         }
         
         // If filterDisplay is provided, then use that instead (e.g. for cases where filterData is a nodeRef)
         filterDisplay = typeof this.currentFilter.filterDisplay !== "undefined" ? this.currentFilter.filterDisplay : (this.currentFilter.filterData || "");
         
         eDescMsg = new Element(document.createElement("div"),
         {
            innerHTML: this.msg("description." + this.currentFilter.filterId, filterDisplay)
         });
         eDescMsg.addClass("message");

         // If filterData is populated and a ".more.filterData" i18n message exists, then use that
         var i18n = "description." + this.currentFilter.filterId + ".more",
            i18nAlt = i18n + ".filterDisplay";
         
         if (filterDisplay !== "" && this.msg(i18nAlt) !== i18nAlt)
         {
            i18n = i18nAlt;
         }

         eDescMore = new Element(document.createElement("span"),
         {
            innerHTML: this.msg(i18n, $html(filterDisplay))
         });
         eDescMore.addClass("more");

         eDescMsg.appendChild(eDescMore);
         eDivDesc = new Element(divDesc);
         eDivDesc.appendChild(eDescMsg);
      },
      
      /**
       * Generates the HTML mark-up for the RSS feed link
       *
       * @method _generateRSSFeedUrl
       * @private
       */
      _generateRSSFeedUrl: function DLTB__generateRSSFeedUrl()
      {
         if (this.widgets.rssFeed && this.modules.docList)
         {
            var params = YAHOO.lang.substitute("{type}/site/{site}/{container}{path}",
            {
               type: this.modules.docList.options.showFolders ? "all" : "documents",
               site: encodeURIComponent(this.options.siteId),
               container: encodeURIComponent(this.options.containerId),
               path: Alfresco.util.encodeURIPath(this.currentPath)
            });

            params += "?filter=" + encodeURIComponent(this.currentFilter.filterId);
            if (this.currentFilter.filterData)
            {
               params += "&filterData=" + encodeURIComponent(this.currentFilter.filterData);             
            }
            params += "&format=rss";
            
            this.widgets.rssFeed.set("href", Alfresco.constants.URL_FEEDSERVICECONTEXT + "components/documentlibrary/feed/" + params);
         }
      }
   }, true);
})();