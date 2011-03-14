/*
 * Copyright 2011 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.samples.retwis.web;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.samples.retwis.Post;
import org.springframework.data.redis.samples.retwis.Range;
import org.springframework.data.redis.samples.retwis.RetwisSecurity;
import org.springframework.data.redis.samples.retwis.redis.RetwisRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Annotation-driven controller for Retwis.
 * 
 * @author Costin Leau
 */
@Controller
public class RetwisController {

	@Autowired
	private final RetwisRepository twitter;

	@Autowired
	public RetwisController(RetwisRepository twitter) {
		this.twitter = twitter;
	}

	@RequestMapping("/")
	public String root(Model model) {
		if (RetwisSecurity.isSignedIn()) {
			return "redirect:/!" + RetwisSecurity.getName();
		}
		return timeline(null, model);
	}

	@RequestMapping("/signUp")
	public String signUp(String name, String pass, String pass2, Model model, HttpServletResponse response) {
		if (twitter.isUserValid(name)) {
			model.addAttribute("errorduplicateuser", Boolean.TRUE);
			return "signin";
		}

		if (!StringUtils.hasText(pass) || !StringUtils.hasText(pass2) || !pass.equals(pass2)) {
			model.addAttribute("errormatch", Boolean.TRUE);
			return "signin";
		}

		String auth = twitter.addUser(name, pass);
		addAuthCookie(auth, name, response);

		return "redirect:/!" + name;
	}

	@RequestMapping("/signIn")
	public String signIn(String name, String pass, Model model, HttpServletResponse response) {
		// add tracing cookie
		if (twitter.auth(name, pass)) {
			addAuthCookie(twitter.addAuth(name), name, response);
			return "redirect:/!" + name;
		}
		else if (StringUtils.hasText(name) || StringUtils.hasText(pass)) {
			model.addAttribute("errorpass", Boolean.TRUE);
		}
		// go back to sign in screen
		return "signin";
	}

	private void addAuthCookie(String auth, String name, HttpServletResponse response) {
		RetwisSecurity.setUser(name, twitter.findUid(name));

		Cookie cookie = new Cookie(CookieInterceptor.RETWIS_COOKIE, auth);
		cookie.setComment("Retwis-J demo");
		// cookie valid for up to 1 week
		cookie.setMaxAge(60 * 60 * 24 * 7);
		response.addCookie(cookie);
	}

	@RequestMapping(value = "/!{name}", method = RequestMethod.GET)
	public String posts(@PathVariable String name, @RequestParam(required = false) String replyto, @RequestParam(required = false) String replypid, @RequestParam(required = false) Integer page, Model model) {
		checkUser(name);
		String targetUid = twitter.findUid(name);
		model.addAttribute("post", new Post());
		model.addAttribute("name", name);
		model.addAttribute("followers", twitter.getFollowers(targetUid));
		model.addAttribute("following", twitter.getFollowing(targetUid));

		if (RetwisSecurity.isSignedIn()) {
			model.addAttribute("replyTo", replyto);
			model.addAttribute("replyPid", replypid);

			if (!targetUid.equals(RetwisSecurity.getUid())) {
				model.addAttribute("also_followed", twitter.alsoFollowed(RetwisSecurity.getUid(), targetUid));
				model.addAttribute("common_followers", twitter.commonFollowers(RetwisSecurity.getUid(), targetUid));
				model.addAttribute("follows", twitter.isFollowing(RetwisSecurity.getUid(), targetUid));
			}
		}
		// sanitize page attribute
		page = (page != null ? Math.abs(page) : 1);
		model.addAttribute("page", page + 1);
		Range range = new Range(page);
		model.addAttribute("moreposts", (RetwisSecurity.isUserSignedIn(targetUid) ? twitter.hasMoreTimeline(targetUid,
				range) : twitter.hasMorePosts(targetUid, range)));
		model.addAttribute("posts", (RetwisSecurity.isUserSignedIn(targetUid) ? twitter.getTimeline(targetUid, range)
				: twitter.getPosts(targetUid, range)));

		return "home";
	}

	@RequestMapping(value = "/!{name}", method = RequestMethod.POST)
	public String posts(@PathVariable String name, WebPost post, Model model, HttpServletRequest request) {
		checkUser(name);
		twitter.post(name, post);
		return "redirect:/!" + name;
	}

	@RequestMapping("/!{name}/follow")
	public String follow(@PathVariable String name) {
		checkUser(name);
		twitter.follow(name);
		return "redirect:/!" + name;
	}

	@RequestMapping("/!{name}/stopfollowing")
	public String stopFollowing(@PathVariable String name) {
		checkUser(name);
		twitter.stopFollowing(name);
		return "redirect:/!" + name;
	}

	@RequestMapping("/!{name}/mentions")
	public String mentions(@PathVariable String name, Model model) {
		checkUser(name);
		model.addAttribute("name", name);
		String targetUid = twitter.findUid(name);

		model.addAttribute("posts", twitter.getMentions(targetUid, new Range()));
		model.addAttribute("followers", twitter.getFollowers(targetUid));
		model.addAttribute("following", twitter.getFollowing(targetUid));

		return "mentions";
	}

	@RequestMapping("/timeline")
	public String timeline(@RequestParam(required = false) Integer page, Model model) {
		// sanitize page attribute
		page = (page != null ? Math.abs(page) : 1);
		model.addAttribute("page", page + 1);
		Range range = new Range(page);
		model.addAttribute("moreposts", twitter.hasMoreTimeline(range));
		model.addAttribute("posts", twitter.timeline(range));
		model.addAttribute("users", twitter.newUsers(new Range()));
		return "timeline";
	}

	@RequestMapping("/logout")
	public String logout() {
		String user = RetwisSecurity.getName();
		// invalidate auth
		twitter.deleteAuth(user);
		return "redirect:/";
	}

	@RequestMapping("/status")
	public String status(String pid, Model model) {
		checkPost(pid);
		model.addAttribute("posts", twitter.getPost(pid));
		return "status";
	}

	private void checkUser(String username) {
		if (!twitter.isUserValid(username)) {
			throw new NoSuchDataException(username, true);
		}
	}

	private void checkPost(String pid) {
		if (!twitter.isPostValid(pid)) {
			throw new NoSuchDataException(pid, false);
		}
	}

	@ExceptionHandler(NoSuchDataException.class)
	public String handleNoUserException(NoSuchDataException ex) {
		//		model.addAttribute("data", ex.getData());
		//		model.addAttribute("nodatatype", ex.isPost() ? "nodata.post" : "nodata.user");
		return "nodata";
	}
}