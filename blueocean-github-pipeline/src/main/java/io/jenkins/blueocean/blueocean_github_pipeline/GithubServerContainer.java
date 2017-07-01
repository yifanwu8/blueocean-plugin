package io.jenkins.blueocean.blueocean_github_pipeline;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import io.jenkins.blueocean.commons.ErrorMessage;
import io.jenkins.blueocean.commons.ServiceException;
import io.jenkins.blueocean.commons.stapler.TreeResponse;
import io.jenkins.blueocean.rest.hal.Link;
import io.jenkins.blueocean.rest.impl.pipeline.scm.ScmServerEndpoint;
import io.jenkins.blueocean.rest.impl.pipeline.scm.ScmServerEndpointContainer;
import net.sf.json.JSONObject;
import org.apache.commons.collections.ComparatorUtils;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.github_branch_source.Endpoint;
import org.jenkinsci.plugins.github_branch_source.GitHubConfiguration;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.json.JsonBody;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.CheckForNull;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GithubServerContainer extends ScmServerEndpointContainer {

    private static final Logger LOGGER = Logger.getLogger(GithubServerContainer.class.getName());

    private final Link parent;

    GithubServerContainer(Link parent) {
        this.parent = parent;
    }

    @POST
    @WebMethod(name="")
    @TreeResponse
    public @CheckForNull GithubServer create(@JsonBody JSONObject request) {
        List<ErrorMessage.Error> errors = Lists.newLinkedList();

        // Validate name
        final String name = (String) request.get(GithubServer.NAME);
        if (StringUtils.isEmpty(name)) {
            errors.add(new ErrorMessage.Error(GithubServer.NAME, ErrorMessage.Error.ErrorCodes.MISSING.toString(), GithubServer.NAME + " is required"));
        } else {
            ScmServerEndpoint byName = findByName(name);
            if (byName != null) {
                errors.add(new ErrorMessage.Error(GithubServer.NAME, ErrorMessage.Error.ErrorCodes.ALREADY_EXISTS.toString(), GithubServer.NAME + " already exists for server at '" + byName.getApiUrl() + "'"));
            }
        }

        // Validate url
        final String url = (String) request.get(GithubServer.API_URL);
        if (StringUtils.isEmpty(url)) {
            errors.add(new ErrorMessage.Error(GithubServer.API_URL, ErrorMessage.Error.ErrorCodes.MISSING.toString(), GithubServer.API_URL + " is required"));
        } else {
            ScmServerEndpoint byUrl = findByURL(url);
            if (byUrl != null) {
                errors.add(new ErrorMessage.Error(GithubServer.API_URL, ErrorMessage.Error.ErrorCodes.ALREADY_EXISTS.toString(), GithubServer.API_URL + " is already registered as '" + byUrl.getName() + "'"));
            }
        }

        if (StringUtils.isNotEmpty(url)) {
            // Validate that the URL represents a Github API endpoint
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Content-type", "application/json");
                connection.connect();
                if (connection.getHeaderField("X-GitHub-Request-Id") == null) {
                    errors.add(new ErrorMessage.Error(GithubServer.API_URL, ErrorMessage.Error.ErrorCodes.INVALID.toString(), "Specified URL is not a Github server"));
                }
            } catch (Throwable e) {
                errors.add(new ErrorMessage.Error(GithubServer.API_URL, ErrorMessage.Error.ErrorCodes.INVALID.toString(), "Could not connect to Github"));
                LOGGER.log(Level.INFO, "Could not connect to Github", e);
            }
        }

        if (!errors.isEmpty()) {
            ErrorMessage errorMessage = new ErrorMessage(400, "Failed to create Github server");
            errorMessage.addAll(errors);
            throw new ServiceException.BadRequestException(errorMessage);
        } else {
            GitHubConfiguration config = GitHubConfiguration.get();
            GithubServer server;
            synchronized (config) {
                Endpoint endpoint = new Endpoint(url, name);
                List<Endpoint> endpoints = Lists.newLinkedList(config.getEndpoints());
                endpoints.add(endpoint);
                config.setEndpoints(endpoints);
                config.save();
                server = new GithubServer(endpoint);
            }
            return server;
        }
     }

    @Override
    public Link getLink() {
        return parent.rel("servers");
    }

    @Override
    public ScmServerEndpoint get(final String name) {
        ScmServerEndpoint githubServer = findByName(name);
        if (githubServer == null) {
            throw new ServiceException.NotFoundException("not found");
        }
        return githubServer;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterator<ScmServerEndpoint> iterator() {
        GitHubConfiguration config = GitHubConfiguration.get();
        List<Endpoint> endpoints;
        synchronized (config) {
            endpoints = Ordering.from(new Comparator<Endpoint>() {
                @Override
                public int compare(Endpoint o1, Endpoint o2) {
                    return ComparatorUtils.NATURAL_COMPARATOR.compare(o1.getName(), o2.getName());
                }
            }).sortedCopy(config.getEndpoints());
        }
        return Iterators.transform(endpoints.iterator(), new Function<Endpoint, ScmServerEndpoint>() {
            @Override
            public ScmServerEndpoint apply(Endpoint input) {
                return new GithubServer(input);
            }
        });
    }

    private ScmServerEndpoint findByName(final String name) {
        return Iterators.find(iterator(), new Predicate<ScmServerEndpoint>() {
            @Override
            public boolean apply(ScmServerEndpoint input) {
                return input.getName().equals(name);
            }
        }, null);
    }

    private ScmServerEndpoint findByURL(final String url) {
        return Iterators.find(iterator(), new Predicate<ScmServerEndpoint>() {
            @Override
            public boolean apply(ScmServerEndpoint input) {
                return input.getApiUrl().equals(url);
            }
        }, null);
    }
}
