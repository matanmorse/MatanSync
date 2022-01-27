/*
 * Copyright (c) 2021, andmcadams
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.andmcadams.wikisync;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.VarbitComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import okhttp3.*;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@PluginDescriptor(
	name = "WikiSync"
)
public class WikiSyncPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private WikiSyncConfig config;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	private OkHttpClient shortTimeoutClient;

	private static final int SECONDS_BETWEEN_UPLOADS = 1;
	private static final int UPLOADS_PER_MANIFEST_CHECK = 2;

	private static final String MANIFEST_URL = "https://sync.runescape.wiki/runelite/manifest";
	private static final String SUBMIT_URL = "https://sync.runescape.wiki/runelite/submit";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private static final int VARBITS_ARCHIVE_ID = 14;
	private Map<Integer, VarbitComposition> varbitCompositions = new HashMap<>();

	public static final String CONFIG_GROUP_KEY = "WikiSync";
	// THIS VERSION SHOULD BE INCREMENTED EVERY RELEASE WHERE WE ADD A NEW TOGGLE
	public static final int VERSION = 1;

	private Manifest manifest;
	private int grabCount = 0;
	private Map<PlayerProfile, PlayerData> playerDataMap = new HashMap<>();

	@Provides
	WikiSyncConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WikiSyncConfig.class);
	}

	@Override
	public void startUp()
	{
		clientThread.invoke(() -> {
			if (client.getIndexConfig() == null)
			{
				return false;
			}
			final int[] varbitIds = client.getIndexConfig().getFileIds(VARBITS_ARCHIVE_ID);
			for (int id : varbitIds)
			{
				varbitCompositions.put(id, client.getVarbit(id));
			}
			return true;
		});
	}

	@Schedule(
		period = SECONDS_BETWEEN_UPLOADS,
		unit = ChronoUnit.SECONDS,
		asynchronous = true
	)
	public void task()
	{
		// TODO: do we want other GameStates?
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		// TODO: find a way to put this somewhere better?
		shortTimeoutClient = okHttpClient.newBuilder()
			.callTimeout(3, TimeUnit.SECONDS)
			.build();

		if (grabCount % UPLOADS_PER_MANIFEST_CHECK == 0)
		{
			checkManifest();
		}

		if (manifest == null)
		{
			// TODO: log something?
			return;
		}

		grabCount++;
		// TODO: check that this doesn't NPE?
		String username = client.getLocalPlayer().getName();
		RuneScapeProfileType profileType = RuneScapeProfileType.getCurrent(client);
		PlayerProfile profileKey = new PlayerProfile(username, profileType);

		PlayerData newPlayerData = getPlayerData();
		PlayerData oldPlayerData = playerDataMap.getOrDefault(profileKey, new PlayerData());
		if (newPlayerData.equals(oldPlayerData))
		{
			return;
		}

		PlayerData delta = diff(newPlayerData, oldPlayerData);
		submitPlayerData(profileKey, delta, newPlayerData);
	}

	private int getVarbitValue(int varbitId)
	{
		VarbitComposition v = varbitCompositions.get(varbitId);
		if (v == null)
		{
			return -1;
		}

		int value = client.getVarpValue(v.getIndex());
		int lsb = v.getLeastSignificantBit();
		int msb = v.getMostSignificantBit();
		int mask = (1 << ((msb - lsb) + 1)) - 1;
		return (value >> lsb) & mask;
	}

	private PlayerData getPlayerData()
	{
		PlayerData out = new PlayerData();

		// IMPORTANT TODO: getVarbitValue must be run on client thread. This code hacks around that.
		for (int varbitId : manifest.varbits)
		{
			out.varb.put(varbitId, getVarbitValue(varbitId));
		}
		for (int varpId : manifest.varps)
		{
			out.varp.put(varpId, client.getVarpValue(varpId));
		}
		for(Skill s : Skill.values())
		{
			out.level.put(s.getName(), client.getRealSkillLevel(s));
		}
		return out;
	}

	private PlayerData diff(PlayerData newPlayerData, PlayerData oldPlayerData)
	{
		return new PlayerData(
				Maps.difference(newPlayerData.varb, oldPlayerData.varb).entriesOnlyOnLeft(),
				Maps.difference(newPlayerData.varp, oldPlayerData.varp).entriesOnlyOnLeft(),
				Maps.difference(newPlayerData.level, oldPlayerData.level).entriesOnlyOnLeft()
		);
	}

	private void submitPlayerData(PlayerProfile profileKey, PlayerData delta, PlayerData newPlayerData)
	{
		PlayerDataSubmission submission = new PlayerDataSubmission(
				profileKey.getUsername(),
				profileKey.getProfileType().name(),
				delta
		);

		Request request = new Request.Builder()
				.url(SUBMIT_URL)
				.post(RequestBody.create(JSON, gson.toJson(submission)))
				.build();

		try (Response response = shortTimeoutClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				// TODO: log something?
				return;
			}
			playerDataMap.put(profileKey, newPlayerData);
		}
		catch (IOException ioException)
		{
			// TODO: log something?
		}
	}

	private void checkManifest()
	{
		Request request = new Request.Builder()
				.url(MANIFEST_URL)
				.build();
		try (Response response = shortTimeoutClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				// TODO: log something?
				return;
			}
			InputStream in = response.body().byteStream();
			manifest = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Manifest.class);
		}
		catch (IOException ioException)
		{
			// TODO: log something?
		}
	}
}
