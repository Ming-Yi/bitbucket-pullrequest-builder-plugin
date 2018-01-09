package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.ApiClient;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BuildState;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.Pullrequest;
import hudson.model.Job;
import hudson.security.ACL;

import java.util.LinkedList;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.common.collect.ImmutableList;

import jenkins.model.Jenkins;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.SCMSourceOwners;

import org.apache.commons.lang.StringUtils;

/**
 * Created by nishio
 */
public class BitbucketRepository {
    private static final Logger logger = Logger.getLogger(BitbucketRepository.class.getName());
    private static final String BUILD_DESCRIPTION = "%s: %s into %s";
    private static final String BUILD_REQUEST_DONE_MARKER = "ttp build flag";
    private static final String BUILD_REQUEST_MARKER_TAG_SINGLE_RX = "\\#[\\w\\-\\d]+";
    private static final String BUILD_REQUEST_MARKER_TAGS_RX = "\\[bid\\:\\s?(.*)\\]";
    /**
     * Default value for comment trigger.
     */
    public static final String DEFAULT_COMMENT_TRIGGER = "test this please";

    private String projectPath;
    private BitbucketPullRequestsBuilder builder;
    private BitbucketBuildTrigger trigger;
    private ApiClient client;
    
    public BitbucketRepository(String projectPath, BitbucketPullRequestsBuilder builder) {
        this.projectPath = projectPath;
        this.builder = builder;
    }

    public void init(Job<?,?> project) {
        this.init(null, null, project);
    }
    
    public <T extends ApiClient.HttpClientFactory> void init(T httpFactory) {
        this.init(null, httpFactory, null);
    }
    
    public void init(ApiClient client) {
        this.init(client, null, null);
    }
    
    public <T extends ApiClient.HttpClientFactory> void init(ApiClient client, T httpFactory, Job<?,?> project) {
        this.trigger = this.builder.getTrigger();
        
        if (client == null) {                      
            String username = trigger.getUsername();
            String password = trigger.getPassword();            
            StandardUsernamePasswordCredentials credentials = getCredentials(project, trigger.getCredentialsId());
            if (credentials != null) {
                username = credentials.getUsername();
                password = credentials.getPassword().getPlainText();
            }            
            this.client = new ApiClient(
                username,
                password,
                trigger.getRepositoryOwner(),
                trigger.getRepositoryName(),
                trigger.getCiKey(),
                trigger.getCiName(),
                httpFactory
            );
            
        } else this.client = client;
    }

    public Collection<Pullrequest> getTargetPullRequests() {
        logger.fine("Fetch PullRequests.");
        List<Pullrequest> pullRequests = client.getPullRequests();
        List<Pullrequest> targetPullRequests = new ArrayList<Pullrequest>();
        for(Pullrequest pullRequest : pullRequests) {
            if (isBuildTarget(pullRequest)) {
                targetPullRequests.add(pullRequest);
            }
        }
        return targetPullRequests;
    }
    
    public ApiClient getClient() {
      return this.client;
    }

    public void addFutureBuildTasks(Collection<Pullrequest> pullRequests) {
        for(Pullrequest pullRequest : pullRequests) {
            if ( this.trigger.getApproveIfSuccess() ) {
                deletePullRequestApproval(pullRequest.getId());
            }
            BitbucketCause cause = new BitbucketCause(
                    pullRequest.getSource().getBranch().getName(),
                    pullRequest.getDestination().getBranch().getName(),
                    pullRequest.getSource().getRepository().getOwnerName(),
                    pullRequest.getSource().getRepository().getRepositoryName(),
                    pullRequest.getId(),
                    pullRequest.getDestination().getRepository().getOwnerName(),
                    pullRequest.getDestination().getRepository().getRepositoryName(),
                    pullRequest.getTitle(),
                    pullRequest.getSource().getCommit().getHash(),
                    pullRequest.getDestination().getCommit().getHash(),
                    pullRequest.getAuthor().getCombinedUsername()
            );
            setBuildStatus(cause, BuildState.INPROGRESS, getInstance().getRootUrl());
            this.builder.getTrigger().startJob(cause);
        }
    }

    private Jenkins getInstance() {
        final Jenkins instance = Jenkins.getInstance();
        if (instance == null){
            throw new IllegalStateException("Jenkins instance is NULL!");
        }
        return instance;
    }


    public void setBuildStatus(BitbucketCause cause, BuildState state, String buildUrl) {
        String comment = null;
        String sourceCommit = cause.getSourceCommitHash();
        String owner = cause.getRepositoryOwner();
        String repository = cause.getRepositoryName();
        String destinationBranch = cause.getTargetBranch();

        logger.fine("setBuildStatus " + state + " for commit: " + sourceCommit + " with url " + buildUrl);

        if (state == BuildState.FAILED || state == BuildState.SUCCESSFUL) {
            comment = String.format(BUILD_DESCRIPTION, builder.getProject().getDisplayName(), sourceCommit, destinationBranch);
        }

        this.client.setBuildStatus(owner, repository, sourceCommit, state, buildUrl, comment, this.builder.getProjectId());
    }

    public void deletePullRequestApproval(String pullRequestId) {
        this.client.deletePullRequestApproval(pullRequestId);
    }

    public void postPullRequestApproval(String pullRequestId) {
        this.client.postPullRequestApproval(pullRequestId);
    }
    
    public String getMyBuildTag(String buildKey) {
      return "#" + this.client.buildStatusKey(buildKey);
    }    
    
    final static Pattern BUILD_TAGS_RX = Pattern.compile(BUILD_REQUEST_MARKER_TAGS_RX, Pattern.CASE_INSENSITIVE | Pattern.CANON_EQ);
    final static Pattern SINGLE_BUILD_TAG_RX = Pattern.compile(BUILD_REQUEST_MARKER_TAG_SINGLE_RX, Pattern.CASE_INSENSITIVE | Pattern.CANON_EQ); 
    final static String CONTENT_PART_TEMPLATE = "```[bid: %s]```";    
    
    private List<String> getAvailableBuildTagsFromTTPComment(String buildTags) {
      logger.log(Level.FINE, "Parse {0}", new Object[]{ buildTags });
      List<String> availableBuildTags = new LinkedList<String>(); 
      Matcher subBuildTagMatcher = SINGLE_BUILD_TAG_RX.matcher(buildTags);
      while(subBuildTagMatcher.find()) availableBuildTags.add(subBuildTagMatcher.group(0).trim());
      return availableBuildTags;
    }
    
    public boolean hasMyBuildTagInTTPComment(String content, String buildKey) {
      Matcher tagsMatcher = BUILD_TAGS_RX.matcher(content);
      if (tagsMatcher.find()) {
        logger.log(Level.FINE, "Content {0} g[1]:{1} mykey:{2}", new Object[] { content, tagsMatcher.group(1).trim(), this.getMyBuildTag(buildKey) });
        return this.getAvailableBuildTagsFromTTPComment(tagsMatcher.group(1).trim()).contains(this.getMyBuildTag(buildKey));
      }
      else return false;
    }        
    
    private void postBuildTagInTTPComment(String pullRequestId, String content, String buildKey) {     
      logger.log(Level.FINE, "Update build tag for {0} build key", buildKey);
      List<String> builds = this.getAvailableBuildTagsFromTTPComment(content);
      builds.add(this.getMyBuildTag(buildKey));      
      content += " " + String.format(CONTENT_PART_TEMPLATE, StringUtils.join(builds, " "));
      logger.log(Level.FINE, "Post comment: {0} with original content {1}", new Object[]{ content, this.client.postPullRequestComment(pullRequestId, content).getId() });
    }
    
    private boolean isTTPComment(String content) {
        // special case: in unit tests, trigger is null and can't be mocked
        String commentTrigger = DEFAULT_COMMENT_TRIGGER;
        if(trigger != null && StringUtils.isNotBlank(trigger.getCommentTrigger())) {
            commentTrigger = trigger.getCommentTrigger();
        }
      return content.toLowerCase().contains(commentTrigger);
    }
    
    private boolean isTTPCommentBuildTags(String content) {
      return content.toLowerCase().contains(BUILD_REQUEST_DONE_MARKER.toLowerCase());
    }
    
    public List<Pullrequest.Comment> filterPullRequestComments(List<Pullrequest.Comment> comments) {
      logger.fine("Filter PullRequest Comments.");
      Collections.sort(comments);
      Collections.reverse(comments);      
      List<Pullrequest.Comment> filteredComments = new LinkedList<Pullrequest.Comment>();      
      for(Pullrequest.Comment comment : comments) {
        String content = comment.getContent();
        if (content == null || content.isEmpty()) continue;        
        boolean isTTP = this.isTTPComment(content);        
        boolean isTTPBuild = this.isTTPCommentBuildTags(content);        
        if (isTTP || isTTPBuild)  filteredComments.add(comment);
        if (isTTP) break;
      }
      return filteredComments;
    }
    
    private boolean isBuildTarget(Pullrequest pullRequest) {
        if (pullRequest.getState() != null && pullRequest.getState().equals("OPEN")) {
            if (isSkipBuild(pullRequest.getTitle()) || !isFilteredBuild(pullRequest)) {
                return false;
            }

            Pullrequest.Revision source = pullRequest.getSource();
            String sourceCommit = source.getCommit().getHash();
            Pullrequest.Revision destination = pullRequest.getDestination();
            String owner = destination.getRepository().getOwnerName();
            String repositoryName = destination.getRepository().getRepositoryName();

            Pullrequest.Repository sourceRepository = source.getRepository();
            String buildKeyPart = this.builder.getProjectId();

            final boolean commitAlreadyBeenProcessed = this.client.hasBuildStatus(
              sourceRepository.getOwnerName(), sourceRepository.getRepositoryName(), sourceCommit, buildKeyPart
            );
            if (commitAlreadyBeenProcessed) logger.log(Level.FINE,
              "Commit {0}#{1} has already been processed", 
              new Object[]{ sourceCommit, buildKeyPart }
            );
            
            final String id = pullRequest.getId();
            List<Pullrequest.Comment> comments = client.getPullRequestComments(owner, repositoryName, id);

            boolean rebuildCommentAvailable = false;
            if (comments != null) {
                Collection<Pullrequest.Comment> filteredComments = this.filterPullRequestComments(comments);
                boolean hasMyBuildTag = false;
                for (Pullrequest.Comment comment : filteredComments) {
                    String content = comment.getContent();
                    if (this.isTTPComment(content)) {  
                        rebuildCommentAvailable = true;
                        logger.log(Level.FINE,
                          "Rebuild comment available for commit {0} and comment #{1}", 
                          new Object[]{ sourceCommit, comment.getId() }
                        );                        
                    }
                    if (isTTPCommentBuildTags(content))
                        hasMyBuildTag |= this.hasMyBuildTagInTTPComment(content, buildKeyPart);
                }
                rebuildCommentAvailable &= !hasMyBuildTag;
            }            
            if (rebuildCommentAvailable) this.postBuildTagInTTPComment(id, "TTP build flag", buildKeyPart);

            final boolean canBuildTarget = rebuildCommentAvailable || !commitAlreadyBeenProcessed;
            logger.log(Level.FINE, "Build target? {0} [rebuild:{1} processed:{2}]", new Object[]{ canBuildTarget, rebuildCommentAvailable, commitAlreadyBeenProcessed});
            return canBuildTarget;
        }

        return false;
    }

    private boolean isSkipBuild(String pullRequestTitle) {
        String skipPhrases = this.trigger.getCiSkipPhrases();
        if (skipPhrases != null && !"".equals(skipPhrases)) {
            String[] phrases = skipPhrases.split(",");
            for(String phrase : phrases) {
                if (pullRequestTitle.toLowerCase().contains(phrase.trim().toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isFilteredBuild(Pullrequest pullRequest) {

        final String pullRequestId = pullRequest.getId();
        final String pullRequestTitle = pullRequest.getTitle();
        final String destinationRepoName = pullRequest.getDestination().getRepository().getRepositoryName();

        // pullRequest.getDestination().getCommit() may return null for pull requests with merge conflicts
        // * see: https://github.com/nishio-dens/bitbucket-pullrequest-builder-plugin/issues/119
        // * see: https://github.com/nishio-dens/bitbucket-pullrequest-builder-plugin/issues/98
        final String destinationCommitHash;
        if (pullRequest.getDestination().getCommit() == null) {
            logger.log(Level.INFO, "Pull request #{0} ''{1}'' in repo ''{2}'' has a null value for destination commit.",
                    new Object[]{pullRequestId, pullRequestTitle, destinationRepoName});
            destinationCommitHash = null;
        } else {
            destinationCommitHash = pullRequest.getDestination().getCommit().getHash();
        }

        BitbucketCause cause = new BitbucketCause(
          pullRequest.getSource().getBranch().getName(),
          pullRequest.getDestination().getBranch().getName(),
          pullRequest.getSource().getRepository().getOwnerName(),
          pullRequest.getSource().getRepository().getRepositoryName(),
          pullRequestId,
          pullRequest.getDestination().getRepository().getOwnerName(),
          destinationRepoName,
          pullRequestTitle,
          pullRequest.getSource().getCommit().getHash(),
          destinationCommitHash,
          pullRequest.getAuthor().getCombinedUsername()
        );
        
        //@FIXME: Way to iterate over all available SCMSources
        List<SCMSource> sources = new LinkedList<SCMSource>();
        for(SCMSourceOwner owner : SCMSourceOwners.all())
          for(SCMSource src : owner.getSCMSources())
            sources.add(src);                
        
        BitbucketBuildFilter filter = !this.trigger.getBranchesFilterBySCMIncludes() ? 
          BitbucketBuildFilter.instanceByString(this.trigger.getBranchesFilter()) :
          BitbucketBuildFilter.instanceBySCM(sources, this.trigger.getBranchesFilter());
        
        return filter.approved(cause);
    }

	private StandardUsernamePasswordCredentials getCredentials(Job<?, ?> project, String credentialsId) {
		if (null == credentialsId)
			return null;
		return CredentialsMatchers
				.firstOrNull(
						CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, project,
								ACL.SYSTEM, ImmutableList.<DomainRequirement> of()),
						CredentialsMatchers.withId(credentialsId));
	}
}
