package hudson.scm;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.scm.IntegritySCM.DescriptorImpl;
import hudson.tasks.Publisher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.Extension;
import hudson.Launcher;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.util.FormValidation;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;

import net.sf.json.JSONObject;

import com.mks.api.response.APIException;
import com.mks.api.response.Response;
import com.mks.api.response.WorkItem;
import com.mks.api.util.Base64;

public class IntegrityCheckpointAction extends Notifier implements IntegrityConfigurable
{
	private String tagName;
	private final Log logger = LogFactory.getLog(getClass());
	private String ipHost;
	private int ipPort;
	private String host;
	private int port;
	private String userName;
	private String password;
	private boolean secure;
	private String configurationName;
	
	@Extension
	public static final IntegrityCheckpointDescriptorImpl CHECKPOINT_DESCRIPTOR = new IntegrityCheckpointDescriptorImpl();

	@DataBoundConstructor
	public IntegrityCheckpointAction(String tagName, String integrationPointHost, int integrationPointPort, String host,
			int port, String userName, String password, boolean secure, String configurationName)
	{
		setTagName(tagName);
		setIntegrationPointHost(integrationPointHost);
		setIntegrationPointPort(integrationPointPort);
		setHost(host);
		setPort(port);
		setUserName(userName);
		setPassword(password);
		setSecure(secure);
		setConfigurationName(configurationName);
	}
	
	/**
	 * Utility function to convert a groovy expression to a string
	 * @param env Environment containing the name/value pairs for substitution
	 * @param expression Groovy expression string
	 * @return Resolved string
	 */
	public static String evalGroovyExpression(Map<String, String> env, String expression)
	{
		Binding binding = new Binding();
		binding.setVariable("env", env);
		binding.setVariable("sys", System.getProperties());
		CompilerConfiguration config = new CompilerConfiguration();
		//config.setDebug(true);
		GroovyShell shell = new GroovyShell(binding, config);
		Object result = shell.evaluate("return \"" + expression + "\"");
		if (result == null)
		{
			return "";
		}
		else
		{
			return result.toString().trim();
		}
	}
	
	/**
	 * Checks if the given value is a valid Integrity Label.
	 * If it's invalid, this method gives you the reason as string.
	 * @param tagName The checkpoint label name
	 * @return the error message, or null if label is valid
	 */
	public static String isInvalidTag(String tagName)
	{
		if (tagName == null || tagName.length() == 0)
		{
			return "The label string is empty!";
		}

		char ch = tagName.charAt(0);
		if (!(('A' <= ch && ch <= 'Z') || ('a' <= ch && ch <= 'z')))
		{
			return "The label must start with an alpha character!";
		}

		for (char invalid : "$,.:;/\\@".toCharArray())
		{
			if (tagName.indexOf(invalid) >= 0)
			{
				return "The label may cannot contain one of the following characters: $ , . : ; / \\ @";
			}
		}

		return null;
	}	
	
	/**
	 * Returns the label pattern for the Checkpoint
	 * @return Checkpoint Label
	 */
	public String getTagName()
	{
		if( tagName == null || tagName.length() == 0 )
		{
			return CHECKPOINT_DESCRIPTOR.getDefaultTagName();
		}

		return tagName;
	}
	
	/**
	 * Sets the label for the Checkpoint
	 * @param tagName The Checkpoint Label
	 */
	public void setTagName(String tagName)
	{
		this.tagName = tagName;
	}
	
	/**
	 * Executes the actual Integrity Checkpoint operation
	 */
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException
	{
		if( ! Result.SUCCESS.equals(build.getResult()) )
		{
			listener.getLogger().println("Build failed!  Skipping Integrity Checkpoint step!");
			return true;
		}

		APISession api = APISession.create(this);
		if( null != api )
		{
			// Evaluate the groovy tag name
			Map<String, String> env = build.getEnvironment(listener);
			String chkptLabel = IntegrityCheckpointAction.evalGroovyExpression(env, tagName);
			try
			{	
        		try
        		{
        			// Get information about the project
        			IntegrityCMProject siProject = IntegritySCM.findProject(getConfigurationName());
        			// Ensure this is not a build project configuration
        			if( ! siProject.isBuild() )
        			{
    					// A checkpoint wasn't done before the build, so lets checkpoint this build now...
        				listener.getLogger().println("Preparing to execute si checkpoint for " + siProject.getConfigurationPath());
        				Response res = siProject.checkpoint(api, chkptLabel);
    					logger.debug(res.getCommandString() + " returned " + res.getExitCode());        					
    					WorkItem wi = res.getWorkItem(siProject.getConfigurationPath());
    					String chkpt = wi.getResult().getField("resultant").getItem().getId();
    					listener.getLogger().println("Successfully checkpointed project " + siProject.getConfigurationPath() + 
    												" with label '" + chkptLabel + "', new revision is " + chkpt);
        			}
        			else
        			{
        				listener.getLogger().println("Cannot checkpoint a build project configuration: " +  siProject.getConfigurationPath() + "!");
        			}
        		}
        		catch(APIException aex)
        		{
            		logger.error("API Exception caught...");
            		ExceptionHandler eh = new ExceptionHandler(aex);
            		logger.error(eh.getMessage());
            		logger.debug(eh.getCommand() + " returned exit code " + eh.getExitCode());
            		throw new Exception(eh.getMessage());
        		}
        		finally
        		{
        			api.Terminate();
        		}
        	}
        	catch (Throwable e) 
        	{
        		e.printStackTrace(listener.fatalError(e.getMessage()));
				logger.error("Exception caught!  " + e);
				return false;
        	}
		}
		else
		{
			logger.error("An API Session could not be established!  Cannot perform checkpoint operation!");
			listener.getLogger().println("An API Session could not be established!  Cannot perform checkpoint operation!");
			return false;
		}

		return true;
	}

	/**
	 * Toggles whether or not this needs to run after build is finalized
	 */
	@Override
	public boolean needsToRunAfterFinalized()
	{
		return true;
	}

	/**
	 * Returns the build step we're monitoring
	 */
	public BuildStepMonitor getRequiredMonitorService()
	{
		return BuildStepMonitor.BUILD;
	}

	/**
	 * Return the instance of DescriptorImpl object for this class
	 */
	@Override
	public BuildStepDescriptor<Publisher> getDescriptor()
	{
		return CHECKPOINT_DESCRIPTOR;
	}
	
	/**
	 * The relationship of Descriptor and IntegrityCheckpointAction (the describable) is akin to class and object.
	 * This means the descriptor is used to create instances of the describable.
	 * Usually the Descriptor is an internal class in the IntegrityCheckpointAction class named DescriptorImpl. 
	 */
    public static class IntegrityCheckpointDescriptorImpl extends BuildStepDescriptor<Publisher> 
    {
    	private static Log desLogger = LogFactory.getLog(IntegrityCheckpointDescriptorImpl.class);
		private String defaultTagName;
		private DescriptorImpl defaults;
    	public IntegrityCheckpointDescriptorImpl()
    	{
        	// Log the construction...
    		super(IntegrityCheckpointAction.class);
			defaults = IntegritySCM.DescriptorImpl.INTEGRITY_DESCRIPTOR;
    		this.defaultTagName = "${env['JOB_NAME']}-${env['BUILD_NUMBER']}-${new java.text.SimpleDateFormat(\"yyyy_MM_dd\").format(new Date())}";
			load();    		
        	desLogger.debug("IntegrityCheckpointAction.IntegrityCheckpointDescriptorImpl() constructed!");        	            
    	}

		@Override
		public Publisher newInstance(StaplerRequest req, JSONObject formData) throws FormException
		{
			IntegrityCheckpointAction chkptAction = (IntegrityCheckpointAction) super.newInstance(req, formData);
			desLogger.debug("IntegrityCheckpointAction.IntegrityCheckpointDescriptorImpl.newInstance() executed!");   
			return chkptAction;
		}    	
    	
		@Override    	
        public String getDisplayName() 
        {
            return "Integrity - CM Checkpoint";
        }

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) throws FormException
		{
			this.defaultTagName = req.getParameter("tagName");
			save();
			desLogger.debug("IntegrityCheckpointAction.IntegrityCheckpointDescriptorImpl.configure() executed!");
			return super.configure(req, formData);
		}

		public boolean isApplicable(@SuppressWarnings("rawtypes") Class<? extends AbstractProject> jobType)
		{
			desLogger.debug("IntegrityCheckpointAction.IntegrityCheckpointDescriptorImpl.isApplicable executed!");
			return true;
		}

		public String getDefaultTagName()
		{
			return defaultTagName;
		}

		public int getDefaultPort()
		{
			return defaults.getDefaultPort();
		}
		
		public String getDefaultHostName()
		{
			return defaults.getDefaultHostName();
		}
		
		public boolean getDeafultSecure()
		{
			return defaults.getDefaultSecure();
		}
		
		public String getDefaultPassword()
		{
			return defaults.getDefaultPassword();
		}
		
		public String getDefaultUserName()
		{
			return defaults.getDefaultUserName();
		}
		
		public String getDefaultIPHostName()
		{
			return defaults.getDefaultIPHostName();
		}
		
		public int getDefaultIPPort()
		{
			return defaults.getDefaultIPPort();
		}
		
		public void setDefaultTagName(String defaultTagName)
		{
			this.defaultTagName = defaultTagName;
		}
		 
		public FormValidation doTagNameCheck(@QueryParameter("value") final String tagName) throws IOException, ServletException
		{
			if( tagName == null || tagName.length() == 0 )
			{
				return FormValidation.error("Please specify a label for this Checkpoint!");
			}
			else
			{
				// Test to make sure the tag name is valid
				String s = null;
				try
				{
					s = evalGroovyExpression(new HashMap<String, String>(), tagName);
				}
				catch(CompilationFailedException e)
				{
					return FormValidation.error("Check if quotes, braces, or brackets are balanced. " + e.getMessage());
				}

				if( null != s )
				{
					String errorMessage = isInvalidTag(s);
					if( null != errorMessage )
					{
						return FormValidation.error(errorMessage);
					}
				}
			}
			return FormValidation.ok();
		}
    }	
    @Override
	public String getIntegrationPointHost() {
		return this.ipHost;
	}

	@Override
	public void setIntegrationPointHost(String host) {
		this.ipHost = host;
	}

	@Override
	public int getIntegrationPointPort() {
		return this.ipPort;
	}

	@Override
	public void setIntegrationPointPort(int port) {
		this.ipPort = port;
	}

	@Override
	public String getHost() {
		return this.host;
	}

	@Override
	public void setHost(String host) {
		this.host = host;
	}

	@Override
	public int getPort() {
		return this.port;
	}

	@Override
	public void setPort(int port) {
		this.port = port;
	}

	@Override
	public String getUserName() {
		return this.userName;
	}

	@Override
	public void setUserName(String username) {
		this.userName = username;
	}

	@Override
	public String getPassword() {
		return Base64.decode(getEncryptedPassword());
	}

	@Override
	public String getEncryptedPassword() {
		return this.password;
	}

	@Override
	public void setPassword(String password) {
		this.password = Base64.encode(password);
	}

	@Override
	public boolean getSecure() {
		return this.secure;
	}

	@Override
	public void setSecure(boolean secure) {
		this.secure = secure;
	}
	@Override
	public String getConfigurationName() {
		return configurationName;
	}
	@Override
	public void setConfigurationName(String configurationName) {
		this.configurationName = configurationName;
	}
}
