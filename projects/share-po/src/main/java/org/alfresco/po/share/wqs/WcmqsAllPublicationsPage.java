package org.alfresco.po.share.wqs;

import org.alfresco.po.share.ShareLink;
import org.alfresco.po.share.SharePage;
import org.alfresco.webdrone.RenderTime;
import org.alfresco.webdrone.RenderWebElement;
import org.alfresco.webdrone.WebDrone;
import org.alfresco.webdrone.exception.PageException;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.List;

import static org.alfresco.webdrone.RenderElement.getVisibleRenderElement;

public class WcmqsAllPublicationsPage extends SharePage
{
    @RenderWebElement
    private final By PAGE_TITLE = By.cssSelector("div.interior-header > h2");
    private final By PUBLICATIONS_TITLES = By.cssSelector(".portfolio-wrapper>li>h3>a");
    private final By KEY_PUBLICATIONS_SECTION = By.cssSelector(".interior-content>h3");
    private final By PUBLICATION_PREVIEWS = By.cssSelector(".img-border");

    /**
     * Constructor.
     * 
     * @param drone WebDriver to access page
     */
    public WcmqsAllPublicationsPage(WebDrone drone)
    {
        super(drone);
    }

    @SuppressWarnings("unchecked")
    @Override
    public WcmqsAllPublicationsPage render(RenderTime timer)
    {
        elementRender(timer, getVisibleRenderElement(KEY_PUBLICATIONS_SECTION));
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public WcmqsAllPublicationsPage render()
    {
        return render(new RenderTime(maxPageLoadingTime));
    }

    @SuppressWarnings("unchecked")
    @Override
    public WcmqsAllPublicationsPage render(final long time)
    {
        return render(new RenderTime(time));
    }
    
    /**
     * Method to get all titles from Publication Page
     * 
     * @return List<ShareLink>
     */
    public List<ShareLink> getAllPublictionsTitles()
    {
        List<ShareLink> folders = new ArrayList<ShareLink>();
        try
        {
            List<WebElement> links = drone.findAll(PUBLICATIONS_TITLES);
            for (WebElement div : links)
            {
                folders.add(new ShareLink(div, drone));
            }
        }
        catch (NoSuchElementException nse)
        {
            throw new PageException("Unable to access news site data", nse);
        }

        return folders;
    }

    public WebElement getKeyPublicationsSection()
    {
        return drone.find(KEY_PUBLICATIONS_SECTION);
    }

    /**
     * Method to get all titles from Publication Page
     *
     * @return List<ShareLink>
     */
    public List<ShareLink> getAllPublictionsImages()
    {
        List<ShareLink> folders = new ArrayList<ShareLink>();
        try
        {
            List<WebElement> links = drone.findAll(PUBLICATION_PREVIEWS);
            for (WebElement div : links)
            {
                folders.add(new ShareLink(div, drone));
            }
        }
        catch (NoSuchElementException nse)
        {
            throw new PageException("Unable to access news site data", nse);
        }

        return folders;
    }


}
