package com.sap.cloud.security.xsuaa.token.authentication;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoderJwkSupport;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.sap.cloud.security.xsuaa.XsuaaServiceConfiguration;

import net.minidev.json.JSONObject;
import org.springframework.util.Assert;

public class XsuaaJwtDecoder implements JwtDecoder {

	Cache<String, JwtDecoder> cache;
	private XsuaaServiceConfiguration xsuaaServiceConfiguration;
	private List<OAuth2TokenValidator<Jwt>> tokenValidators = new ArrayList<>();

	XsuaaJwtDecoder(XsuaaServiceConfiguration xsuaaServiceConfiguration, int cacheValidityInSeconds, int cacheSize,
			OAuth2TokenValidator<Jwt>... tokenValidators) {
		cache = Caffeine.newBuilder().expireAfterWrite(cacheValidityInSeconds, TimeUnit.SECONDS).maximumSize(cacheSize)
				.build();
		this.xsuaaServiceConfiguration = xsuaaServiceConfiguration;
		// configure token validators
		this.tokenValidators.add(new JwtTimestampValidator());

		if (tokenValidators == null) {
			this.tokenValidators.add(new XsuaaAudienceValidator(xsuaaServiceConfiguration));
		} else {
			this.tokenValidators.addAll(Arrays.asList(tokenValidators));
		}
	}

	@Override
	public Jwt decode(String token) throws JwtException {
		Assert.notNull(token, "token is required");
		try {
			JWT jwt = JWTParser.parse(token);
			String subdomain = getSubdomain(jwt);

			String zid = jwt.getJWTClaimsSet().getStringClaim("zid");
			JwtDecoder decoder = cache.get(subdomain, k -> this.getDecoder(zid, subdomain));
			return decoder.decode(token);
		} catch (ParseException ex) {
			throw new JwtException("Error initializing JWT decoder:" + ex.getMessage());
		}
	}

	protected JwtDecoder getDecoder(String zid, String subdomain) {
		String url = xsuaaServiceConfiguration.getTokenKeyUrl(zid, subdomain);
		NimbusJwtDecoderJwkSupport decoder = new NimbusJwtDecoderJwkSupport(url);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(tokenValidators));
		return decoder;
	}

	protected String getSubdomain(JWT jwt) throws ParseException {
		String subdomain = "";
		JSONObject extAttr = jwt.getJWTClaimsSet().getJSONObjectClaim("ext_attr");
		if (extAttr != null && extAttr.getAsString("zdn") != null) {
			subdomain = extAttr.getAsString("zdn");
		}
		return subdomain;
	}

}
