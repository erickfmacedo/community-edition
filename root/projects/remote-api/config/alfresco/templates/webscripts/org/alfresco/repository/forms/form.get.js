function main()
{
	// Extract template args
	var ta_storeType = url.templateArgs['store_type'];
	var ta_storeId = url.templateArgs['store_id'];
	var ta_id = url.templateArgs['id'];
	var ta_path = url.templateArgs['path'];
	
    if (logger.isLoggingEnabled())
    {
       logger.log("ta_storeType = " + ta_storeType);
       logger.log("ta_storeId = " + ta_storeId);
       logger.log("ta_id = " + ta_id);
       logger.log("ta_path = " + ta_path);
    }

	var formUrl = '';
	// The template argument 'path' only appears in the second URI template.
	if (ta_path != null)
	{
		//TODO Need to test this path.
		formUrl = ta_path;
	}
	else
	{
		formUrl = ta_storeType + '://' + ta_storeId + '/' + ta_id;
	}

    if (logger.isLoggingEnabled())
    {
       logger.log("formUrl = " + formUrl);
    }
    
   	var formScriptObj = formService.getForm(formUrl);
	
	if (formScriptObj == null)
	{
        var message = "The form for item \"" + formUrl + "\" could not be found.";
        if (logger.isWarnLoggingEnabled())
        {
           logger.warn(message);
        }
        status.setCode(404, message);
        return;
	}
	
    var formModel = {};
    formModel.data = {};

    formModel.data.item = '/api/node/' + ta_storeType + '/' + ta_storeId + '/' + ta_id;
    formModel.data.submissionUrl = '/api/forms/node/' + ta_storeType + '/' + ta_storeId + '/' + ta_id;
    formModel.data.type = formScriptObj.type;
    
    formModel.data.definition = {};
    formModel.data.definition.fields = [];
    
    // We're explicitly listing the object fields of FieldDefinition.java and its subclasses here.
	// I don't see a way to get these dynamically at runtime.
	var supportedBaseFieldNames = ['name', 'label', 'description', 'binding',
	                               'defaultValue', 'group', 'protectedField'];
	var supportedPropertyFieldNames = ['dataType', 'mandatory',
	                                   'repeats', 'constraints'];
	var supportedAssociationFieldNames = ['endpointType', 'endpointDirection',
	                                      'endpointMandatory', 'endpointMany'];
	
	var allSupportedFieldNames = supportedBaseFieldNames
	    .concat(supportedPropertyFieldNames)
	    .concat(supportedAssociationFieldNames);
	
	var fieldDefs = formScriptObj.fieldDefinitions;
    for (var x = 0; x < fieldDefs.length; x++)
    {
    	var fieldDef = fieldDefs[x];
    	var field = {};
    	
    	for (var i = 0; i < allSupportedFieldNames.length; i++) 
    	{
    		var nextSupportedName = allSupportedFieldNames[i];
    		var nextValue = fieldDef[nextSupportedName];
    		
    		if (nextValue != null) 
    		{
    			field[nextSupportedName] = nextValue;
    		}
    	}
    	
    	field.type = (fieldDef.dataType != null) ? "property" : "association";
    	formModel.data.definition.fields.push(field);
    }

    formModel.data.formData = {};
    for (var k in formScriptObj.formData.data)
    {
        var value = formScriptObj.formData.data[k].value;

        if (value instanceof java.util.Date)
        {
            formModel.data.formData[k.replace(/:/g, "_")] = utils.toISO8601(value);
        }
        // There is no need to handle java.util.List instances here as they are
        // returned from ScriptFormData.java as Strings
        else
        {
            formModel.data.formData[k.replace(/:/g, "_")] = value;
        }
    }

    model.form = formModel;
}

main();
