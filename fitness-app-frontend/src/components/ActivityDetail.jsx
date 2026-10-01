import React, { useEffect, useState } from "react";
import { useParams } from "react-router";
import { getActivityDetail } from "../services/api";
import {
  Box,
  Chip,
  CircularProgress,
  Paper,
  Stack,
  Typography,
} from "@mui/material";
import { alpha } from "@mui/material/styles";

const POLL_INTERVAL_MS = 3000;
// the recommendation is generated asynchronously (RabbitMQ -> AI service -> Gemini), so allow about two minutes
const MAX_POLLS = 40;

// AI items come as "Title: explanation"; show the title in bold when it looks like one
const splitItem = (item) => {
  const match = /^([^:]{2,60}):\s+([\s\S]+)$/.exec(item);
  return match ? { title: match[1], body: match[2] } : { title: null, body: item };
};

const formatType = (type) =>
  (type || "")
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");

const formatGenerated = (value) => {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? null
    : date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
};

const hasItems = (list) => Array.isArray(list) && list.length > 0;

const cardSx = {
  p: { xs: 2.5, sm: 4 },
  borderRadius: 3,
  border: 1,
  borderColor: "divider",
  boxShadow: "0 1px 3px rgba(16, 24, 40, 0.06), 0 1px 2px rgba(16, 24, 40, 0.04)",
};

// tone = MUI palette colour used for the accent (primary / info / success / warning)
const Section = ({ title, description, tone = "primary", highlighted = false, children }) => (
  <Paper
    elevation={0}
    component="section"
    sx={{
      ...cardSx,
      ...(highlighted && {
        borderColor: `${tone}.main`,
        borderLeftWidth: 5,
        bgcolor: (theme) => alpha(theme.palette[tone].main, 0.06),
      }),
    }}
  >
    <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: description ? 0.75 : 2.5 }}>
      <Box sx={{ width: 4, height: 26, borderRadius: 2, bgcolor: `${tone}.main`, flexShrink: 0 }} />
      <Typography variant="h5" component="h2" sx={{ fontWeight: 700, fontSize: { xs: "1.25rem", sm: "1.45rem" } }}>
        {title}
      </Typography>
    </Stack>
    {description && (
      <Typography color="text.secondary" sx={{ mb: 3, ml: "22px", lineHeight: 1.6 }}>
        {description}
      </Typography>
    )}
    {children}
  </Paper>
);

const AiItem = ({ text }) => {
  const { title, body } = splitItem(text);
  return (
    <Box>
      {title && (
        <Typography sx={{ fontWeight: 700, fontSize: "1.05rem", mb: 0.5 }}>{title}</Typography>
      )}
      <Typography sx={{ lineHeight: 1.8, whiteSpace: "pre-line" }}>{body}</Typography>
    </Box>
  );
};

const BulletList = ({ items, tone = "primary" }) => (
  <Stack component="ul" spacing={3} sx={{ listStyle: "none", m: 0, p: 0 }}>
    {items.map((item, index) => (
      <Stack component="li" key={index} direction="row" spacing={2} alignItems="flex-start">
        {/* padding, not margin: Stack resets the margin of its children */}
        <Box sx={{ pt: "0.6rem", flexShrink: 0 }}>
          <Box sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: `${tone}.main` }} />
        </Box>
        <AiItem text={item} />
      </Stack>
    ))}
  </Stack>
);

const CardGrid = ({ items, columns = 1 }) => (
  <Box
    sx={{
      display: "grid",
      gap: 2.5,
      gridTemplateColumns: { xs: "1fr", md: columns === 2 ? "repeat(2, 1fr)" : "1fr" },
    }}
  >
    {items.map((item, index) => (
      <Box
        key={index}
        sx={{
          p: 2.5,
          borderRadius: 2,
          border: 1,
          borderColor: "divider",
          borderLeft: 4,
          borderLeftColor: "primary.main",
          bgcolor: (theme) => alpha(theme.palette.primary.main, 0.03),
        }}
      >
        <AiItem text={item} />
      </Box>
    ))}
  </Box>
);

const StepList = ({ items }) => (
  <Stack spacing={3}>
    {items.map((step, index) => (
      <Stack direction="row" spacing={2.5} key={index} alignItems="flex-start">
        <Box
          sx={{
            width: 32,
            height: 32,
            borderRadius: "50%",
            bgcolor: "primary.main",
            color: "primary.contrastText",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            fontSize: 15,
            fontWeight: 700,
            flexShrink: 0,
          }}
        >
          {index + 1}
        </Box>
        <AiItem text={step} />
      </Stack>
    ))}
  </Stack>
);

const PageHeader = ({ recommendation, timeLabel = "Generated" }) => {
  const generated = recommendation ? formatGenerated(recommendation.createdAt) : null;
  return (
    <Paper
      elevation={0}
      component="header"
      sx={{
        ...cardSx,
        background: (theme) =>
          `linear-gradient(135deg, ${alpha(theme.palette.primary.main, 0.12)}, ${alpha(theme.palette.primary.main, 0.02)})`,
      }}
    >
      <Typography variant="overline" color="primary" sx={{ fontWeight: 700, letterSpacing: 1.4 }}>
        AI-powered analysis
      </Typography>
      <Typography
        variant="h4"
        component="h1"
        sx={{ fontWeight: 800, mb: 2, fontSize: { xs: "1.7rem", sm: "2.2rem" } }}
      >
        AI Fitness Recommendation
      </Typography>
      {recommendation && (
        <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
          <Chip label={formatType(recommendation.activityType)} color="primary" sx={{ fontWeight: 700, fontSize: "0.95rem" }} />
          {generated && (
            <Typography color="text.secondary">{timeLabel} {generated}</Typography>
          )}
        </Stack>
      )}
    </Paper>
  );
};

const ActivityDetail = () => {
  const { id } = useParams();

  const [recommendation, setRecommendation] = useState(null);
  const [status, setStatus] = useState("loading"); // loading | ready | missing

  useEffect(() => {
    let cancelled = false;
    let timer;

    const load = (attempt) => {
      getActivityDetail(id)
        .then((response) => {
          if (cancelled) return;
          setRecommendation(response.data);
          setStatus("ready");
        })
        .catch((error) => {
          if (cancelled) return;
          if (attempt < MAX_POLLS) {
            timer = setTimeout(() => load(attempt + 1), POLL_INTERVAL_MS);
          } else {
            console.error(error);
            setStatus("missing");
          }
        });
    };

    load(1);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [id]);

  const container = (children) => (
    <Box sx={{ maxWidth: 920, mx: "auto", p: { xs: 0, sm: 2 }, textAlign: "left" }}>
      <Stack spacing={{ xs: 2.5, sm: 3 }}>{children}</Stack>
    </Box>
  );

  if (!recommendation && status === "loading") {
    return (
      <Stack alignItems="center" spacing={2} sx={{ py: 10 }}>
        <CircularProgress />
        <Typography variant="h6">Generating your AI recommendation...</Typography>
        <Typography color="text.secondary">
          Gemini is analyzing your activity. This usually takes under a minute.
        </Typography>
      </Stack>
    );
  }

  if (!recommendation) {
    return container(
      <>
        <PageHeader />
        <Section tone="warning" highlighted title="No recommendation yet">
          <Typography sx={{ lineHeight: 1.8, fontSize: "1.05rem" }}>
            Your activity has been recorded successfully, but its AI recommendation is not available yet. Please check again shortly.
          </Typography>
        </Section>
      </>
    );
  }

  if (recommendation.aiGenerated === false) {
    return container(
      <>
        <PageHeader recommendation={recommendation} timeLabel="Last attempt" />
        <Section tone="warning" highlighted title="AI analysis unavailable">
          <Typography sx={{ lineHeight: 1.8, fontSize: "1.05rem" }}>{recommendation.recommendation}</Typography>
          {recommendation.failureReason && (
            <Typography color="text.secondary" sx={{ mt: 2, wordBreak: "break-word", lineHeight: 1.7 }}>
              <strong>Technical detail:</strong> {recommendation.failureReason}
            </Typography>
          )}
        </Section>
      </>
    );
  }

  const paragraphs = (recommendation.recommendation || "")
    .split(/\n+/)
    .map((paragraph) => paragraph.trim())
    .filter(Boolean);

  return container(
    <>
      <PageHeader recommendation={recommendation} />

      <Section title="Overall Analysis" description="How this session looks based on the data you logged">
        <Stack spacing={2.5}>
          {paragraphs.map((paragraph, index) => (
            <Typography key={index} sx={{ lineHeight: 1.85, fontSize: index === 0 ? "1.1rem" : "1.05rem" }}>
              {paragraph}
            </Typography>
          ))}
        </Stack>
      </Section>

      {hasItems(recommendation.performanceInsights) && (
        <Section title="Performance Insights" description="What your numbers suggest about effort and intensity">
          <CardGrid items={recommendation.performanceInsights} columns={2} />
        </Section>
      )}

      {hasItems(recommendation.improvements) && (
        <Section title="Improvements" description="Where you can get more out of your next sessions, and why it matters">
          <BulletList items={recommendation.improvements} />
        </Section>
      )}

      {hasItems(recommendation.nextSteps) && (
        <Section title="Next Steps" description="Concrete actions to take over the coming days">
          <StepList items={recommendation.nextSteps} />
        </Section>
      )}

      {hasItems(recommendation.suggestions) && (
        <Section title="Training Recommendations" description="How to progress your training from here">
          <CardGrid items={recommendation.suggestions} />
        </Section>
      )}

      {hasItems(recommendation.recovery) && (
        <Section title="Recovery" description="Help your body recover and adapt after this session" tone="info" highlighted>
          <BulletList items={recommendation.recovery} tone="info" />
        </Section>
      )}

      {hasItems(recommendation.nutrition) && (
        <Section
          title="Nutrition & Diet"
          description="General food and hydration guidance for this kind of activity - not medical advice"
          tone="success"
          highlighted
        >
          <BulletList items={recommendation.nutrition} tone="success" />
        </Section>
      )}

      {hasItems(recommendation.safety) && (
        <Section title="Safety Guidelines" description="Keep these in mind to stay safe" tone="warning" highlighted>
          <BulletList items={recommendation.safety} tone="warning" />
        </Section>
      )}

      {recommendation.summary && (
        <Section title="Summary" description="The most important takeaways" highlighted>
          <Typography sx={{ lineHeight: 1.85, fontSize: "1.12rem", fontWeight: 500 }}>{recommendation.summary}</Typography>
        </Section>
      )}

      <Typography variant="body2" color="text.secondary" sx={{ textAlign: "center", pb: 2 }}>
        Generated by AI from your logged activity data. General guidance only - not medical advice.
      </Typography>
    </>
  );
};

export default ActivityDetail;
